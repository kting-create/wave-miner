package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.store.ArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskRunnerTest {

    @TempDir
    Path tmp;

    private TaskRepository repo;
    private TaskRunner runner;
    private final Map<StageType, RuntimeException> failures = new ConcurrentHashMap<>();
    private final CountDownLatch downloadEntered = new CountDownLatch(1);
    private volatile CountDownLatch downloadBlocker;
    private final AtomicInteger downloadInvocations = new AtomicInteger();
    private final AtomicInteger extractInvocations = new AtomicInteger();
    private volatile boolean extractSawDownloadArtifact;
    private volatile boolean downloadWaitsForCancel;

    @BeforeEach
    void setUp() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("runner-test.db")));
        jdbc.execute("CREATE TABLE IF NOT EXISTS task (id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_ref TEXT, title TEXT NOT NULL, stages TEXT NOT NULL, status TEXT NOT NULL, error_message TEXT, created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_stage (task_id TEXT NOT NULL, stage_type TEXT NOT NULL, status TEXT NOT NULL, detail TEXT, artifact_path TEXT, PRIMARY KEY (task_id, stage_type))");
        repo = new TaskRepository(jdbc);
        var props = new com.waveminer.waveminer.config.AppProperties();
        props.setDataDir(tmp.toString());
        var store = new ArtifactStore(props);
        failures.clear();

        StageHandler download = new StageHandler() {
            @Override public StageType type() { return StageType.DOWNLOAD; }
            @Override public void run(TaskContext ctx) {
                downloadInvocations.incrementAndGet();
                if (failures.containsKey(StageType.DOWNLOAD)) throw failures.get(StageType.DOWNLOAD);
                downloadEntered.countDown();
                CountDownLatch gate = downloadBlocker;
                if (gate != null) {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
                if (downloadWaitsForCancel) {
                    while (!ctx.cancelRequested()) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }
                    throw new TaskCancelledException();
                }
                try {
                    Files.writeString(ctx.dir().resolve("source.mp4"), "v");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                ctx.putArtifact(StageType.DOWNLOAD, "source.mp4");
            }
        };
        StageHandler extract = new StageHandler() {
            @Override public StageType type() { return StageType.AUDIO_EXTRACT; }
            @Override public void run(TaskContext ctx) {
                extractInvocations.incrementAndGet();
                if (failures.containsKey(StageType.AUDIO_EXTRACT)) throw failures.get(StageType.AUDIO_EXTRACT);
                extractSawDownloadArtifact = ctx.artifactOf(StageType.DOWNLOAD) != null;
                ctx.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
            }
        };
        runner = new TaskRunner(props, List.of(download, extract), repo, store);
    }

    private Task insertTask(String id) {
        Task t = new Task();
        t.setId(id);
        t.setSourceType(SourceType.URL);
        t.setSourceRef("https://e.com/a.mp4");
        t.setTitle("a.mp4");
        t.setStages(List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT));
        t.setStatus(TaskStatus.PENDING);
        t.setCreatedAt(Instant.now());
        repo.insert(t);
        repo.insertStages(id, t.getStages());
        return t;
    }

    @Test
    void runsAllStagesToCompletion() {
        insertTask("ok");
        runner.submit(repo.find("ok").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("ok").orElseThrow().getStatus() == TaskStatus.COMPLETED);
        assertThat(repo.stagesOf("ok")).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(StageStatus.DONE));
    }

    @Test
    void firstRunStillInvokesEveryStageHandler() {
        insertTask("fresh");
        runner.submit(repo.find("fresh").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("fresh").orElseThrow().getStatus() == TaskStatus.COMPLETED);
        assertThat(downloadInvocations.get()).isEqualTo(1);
        assertThat(extractInvocations.get()).isEqualTo(1);
    }

    @Test
    void retryResumesFromFirstNonDoneStageAndRestoresUpstreamArtifact() {
        insertTask("retry");
        failures.put(StageType.AUDIO_EXTRACT, new RuntimeException("转码崩溃"));
        runner.submit(repo.find("retry").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("retry").orElseThrow().getStatus() == TaskStatus.FAILED);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !runner.isInFlight("retry"));
        int downloadsBefore = downloadInvocations.get();

        failures.remove(StageType.AUDIO_EXTRACT);
        repo.resetTaskForRetry("retry");
        repo.resetStagesForRetry("retry");
        runner.submit(repo.find("retry").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("retry").orElseThrow().getStatus() == TaskStatus.COMPLETED);

        assertThat(downloadInvocations.get()).isEqualTo(downloadsBefore);
        assertThat(extractSawDownloadArtifact).isTrue();
        var stages = repo.stagesOf("retry");
        assertThat(stages.get(0).getStatus()).isEqualTo(StageStatus.DONE);
        assertThat(stages.get(0).getArtifactPath()).isEqualTo("source.mp4");
        assertThat(stages.get(1).getStatus()).isEqualTo(StageStatus.DONE);
    }

    @Test
    void failureInSecondStageKeepsFirstArtifactAndMarksFailed() {
        insertTask("half");
        failures.put(StageType.AUDIO_EXTRACT, new RuntimeException("转码崩溃"));
        runner.submit(repo.find("half").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("half").orElseThrow().getStatus() == TaskStatus.FAILED);
        var stages = repo.stagesOf("half");
        assertThat(stages.get(0).getStatus()).isEqualTo(StageStatus.DONE);
        assertThat(stages.get(0).getArtifactPath()).isEqualTo("source.mp4");
        assertThat(stages.get(1).getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(stages.get(1).getDetail()).contains("转码崩溃");
        assertThat(repo.find("half").orElseThrow().getErrorMessage()).contains("转码崩溃");
    }

    @Test
    void cancelBeforeRunMarksCancelled() {
        insertTask("cc");
        runner.cancel("cc");
        runner.submit(repo.find("cc").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("cc").orElseThrow().getStatus() == TaskStatus.CANCELLED);
    }

    @Test
    void cancelDuringRunningStageMarksCancelledWithFailedStage() throws Exception {
        insertTask("cx");
        downloadWaitsForCancel = true;
        runner.submit(repo.find("cx").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.stagesOf("cx").get(0).getStatus() == StageStatus.RUNNING);
        assertThat(runner.cancel("cx")).isTrue();
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("cx").orElseThrow().getStatus() == TaskStatus.CANCELLED);
        var stages = repo.stagesOf("cx");
        assertThat(stages.get(0).getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(stages.get(0).getDetail()).isEqualTo("已取消");
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !runner.isInFlight("cx"));
    }

    @Test
    void duplicateSubmitWhileInFlightIsRefusedAndFirstRunCompletes() throws Exception {
        insertTask("dup");
        CountDownLatch gate = new CountDownLatch(1);
        downloadBlocker = gate;
        runner.submit(repo.find("dup").orElseThrow());
        assertThat(downloadEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.isInFlight("dup")).isTrue();
        assertThatThrownBy(() -> runner.submit(repo.find("dup").orElseThrow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("任务正在执行中，请勿重复提交");
        gate.countDown();
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("dup").orElseThrow().getStatus() == TaskStatus.COMPLETED);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> !runner.isInFlight("dup"));
        assertThat(repo.stagesOf("dup")).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(StageStatus.DONE));
    }

    @Test
    void missingHandlerFailsTask() {
        insertTask("mh");
        TaskRunner solo = new TaskRunner(new com.waveminer.waveminer.config.AppProperties(),
                List.of(), repo, new ArtifactStore(props()));
        solo.submit(repo.find("mh").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> repo.find("mh").orElseThrow().getStatus() == TaskStatus.FAILED);
        assertThat(repo.find("mh").orElseThrow().getErrorMessage()).contains("处理器");
    }

    private com.waveminer.waveminer.config.AppProperties props() {
        var props = new com.waveminer.waveminer.config.AppProperties();
        props.setDataDir(tmp.toString());
        return props;
    }
}
