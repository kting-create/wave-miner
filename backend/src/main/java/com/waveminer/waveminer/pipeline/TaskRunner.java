package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.store.ArtifactStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskRunner implements PipelineService.Runner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final Semaphore permits;
    private final Map<StageType, StageHandler> handlers;
    private final TaskRepository repo;
    private final ArtifactStore store;
    private final TaskEventPublisher events;
    private final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public TaskRunner(AppProperties props, List<StageHandler> handlers,
                      TaskRepository repo, ArtifactStore store, TaskEventPublisher events) {
        this.permits = new Semaphore(Math.max(1, props.getMaxConcurrentTasks()));
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(StageHandler::type, Function.identity()));
        this.repo = repo;
        this.store = store;
        this.events = events;
    }

    @Override
    public void submit(Task task) {
        if (!inFlight.add(task.getId())) {
            throw new IllegalStateException("任务正在执行中，请勿重复提交");
        }
        try {
            repo.updateTaskStatus(task.getId(), TaskStatus.PENDING, null);
            cancels.computeIfAbsent(task.getId(), k -> new AtomicBoolean(false));
            Thread.ofVirtual().name("wave-miner-task-" + task.getId()).start(() -> runTask(task.getId()));
        } catch (RuntimeException e) {
            inFlight.remove(task.getId());
            throw e;
        }
    }

    public boolean isInFlight(String taskId) {
        return inFlight.contains(taskId);
    }

    public boolean cancel(String taskId) {
        AtomicBoolean flag = cancels.get(taskId);
        if (flag == null) {
            boolean pending = repo.find(taskId)
                    .map(t -> t.getStatus() == TaskStatus.PENDING)
                    .orElse(false);
            if (!pending) return false;
            flag = cancels.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        }
        flag.set(true);
        return true;
    }

    private void runTask(String taskId) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancels.remove(taskId);
            inFlight.remove(taskId);
            return;
        }
        try {
            execute(taskId);
        } catch (Exception e) {
            log.error("任务执行异常 task={}", taskId, e);
            repo.updateTaskStatus(taskId, TaskStatus.FAILED, "内部错误: " + e.getMessage());
            events.publish(TaskEventPublisher.TaskEvent.task(taskId, "FAILED"));
            repo.updateFinishedAt(taskId, Instant.now());
        } finally {
            cancels.remove(taskId);
            inFlight.remove(taskId);
            permits.release();
        }
    }

    private void execute(String taskId) {
        Task task = repo.find(taskId).orElse(null);
        AtomicBoolean cancel = cancels.get(taskId);
        if (task == null || cancel == null) return;

        if (cancel.get()) {
            repo.updateTaskStatus(taskId, TaskStatus.CANCELLED, null);
            events.publish(TaskEventPublisher.TaskEvent.task(taskId, "CANCELLED"));
            repo.updateFinishedAt(taskId, Instant.now());
            return;
        }

        repo.updateTaskStatus(taskId, TaskStatus.RUNNING, null);
        repo.updateStartedAt(taskId, Instant.now());

        TaskContext ctx = new TaskContext(
                taskId,
                store.taskDir(taskId),
                task.getSourceType() == SourceType.URL ? task.getSourceRef() : null,
                task.getSourceType() == SourceType.UPLOAD
                        ? TaskContext.findUploadedFile(store.taskDir(taskId)) : null,
                cancel,
                task.getEngineId(),
                task.getLanguage(),
                task.isDiarize());

        Map<StageType, TaskStage> stored = repo.stagesOf(taskId).stream()
                .collect(Collectors.toMap(TaskStage::getStageType, Function.identity()));

        for (StageType type : task.getStages()) {
            if (cancel.get()) {
                markRemainingSkipped(task, type);
                repo.updateTaskStatus(taskId, TaskStatus.CANCELLED, null);
                events.publish(TaskEventPublisher.TaskEvent.task(taskId, "CANCELLED"));
                repo.updateFinishedAt(taskId, Instant.now());
                return;
            }
            TaskStage previous = stored.get(type);
            if (previous != null && previous.getStatus() == StageStatus.DONE
                    && previous.getArtifactPath() != null && !previous.getArtifactPath().isBlank()) {
                ctx.putArtifact(type, previous.getArtifactPath());
                continue;
            }
            StageHandler handler = handlers.get(type);
            if (handler == null) {
                fail(taskId, type, "没有可用的处理器: " + type);
                return;
            }
            repo.updateStage(taskId, type, StageStatus.RUNNING, null, null);
            events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "RUNNING", null));
            try {
                handler.run(ctx);
            } catch (TaskCancelledException e) {
                repo.updateStage(taskId, type, StageStatus.FAILED, "已取消", null);
                events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "FAILED", "已取消"));
                repo.updateTaskStatus(taskId, TaskStatus.CANCELLED, null);
                events.publish(TaskEventPublisher.TaskEvent.task(taskId, "CANCELLED"));
                repo.updateFinishedAt(taskId, Instant.now());
                return;
            } catch (Exception e) {
                fail(taskId, type, e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }
            repo.updateStage(taskId, type, StageStatus.DONE, null, ctx.artifactNameOf(type));
            events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "DONE", null));
        }

        repo.updateTaskStatus(taskId, TaskStatus.COMPLETED, null);
        events.publish(TaskEventPublisher.TaskEvent.task(taskId, "COMPLETED"));
        repo.updateFinishedAt(taskId, Instant.now());
    }

    private void fail(String taskId, StageType type, String message) {
        repo.updateStage(taskId, type, StageStatus.FAILED, message, null);
        events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "FAILED", message));
        repo.updateTaskStatus(taskId, TaskStatus.FAILED, message);
        events.publish(TaskEventPublisher.TaskEvent.task(taskId, "FAILED"));
        repo.updateFinishedAt(taskId, Instant.now());
    }

    private void markRemainingSkipped(Task task, StageType from) {
        boolean skipping = false;
        for (StageType type : task.getStages()) {
            if (type == from) skipping = true;
            if (skipping) repo.updateStage(task.getId(), type, StageStatus.SKIPPED, "已取消", null);
        }
    }
}
