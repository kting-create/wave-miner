package com.waveminer.waveminer.api;

import com.waveminer.waveminer.pipeline.TaskEventPublisher;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@Component
public class TaskSseRegistry implements TaskEventPublisher.Listener {

    private static final Logger log = LoggerFactory.getLogger(TaskSseRegistry.class);
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public TaskSseRegistry(TaskEventPublisher publisher) {
        publisher.addListener(this);
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(mapper.writeValueAsString(new Connected(taskId))));
        } catch (IOException e) {
            list.remove(emitter);
            emitter.complete();
        }
        return emitter;
    }

    @Override
    public void onEvent(TaskEventPublisher.TaskEvent e) {
        List<SseEmitter> list = emitters.get(e.taskId());
        if (list == null || list.isEmpty()) return;
        boolean terminal = "task".equals(e.type()) && TERMINAL.contains(e.status());
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(mapper.writeValueAsString(e)));
                if (terminal) {
                    emitter.complete();
                    list.remove(emitter);
                }
            } catch (IOException | IllegalStateException ex) {
                list.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已断开的连接
                }
            }
        }
        if (list.isEmpty()) {
            emitters.remove(e.taskId(), list);
        }
    }

    int emitterCount(String taskId) {
        List<SseEmitter> list = emitters.get(taskId);
        return list == null ? 0 : list.size();
    }

    private void sendHeartbeats() {
        for (Map.Entry<String, List<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (Exception ex) {
                    entry.getValue().remove(emitter);
                    log.debug("SSE 心跳失败，移除连接: {}", ex.getMessage());
                }
            }
        }
    }

    private record Connected(String taskId, String type) {
        private Connected(String taskId) {
            this(taskId, "connected");
        }
    }
}
