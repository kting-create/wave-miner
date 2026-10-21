package com.waveminer.waveminer.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskEventPublisher {

    public record TaskEvent(String taskId, String type, String stage, String status, String message, Instant at) {
        public static TaskEvent stage(String taskId, String stage, String status, String message) {
            return new TaskEvent(taskId, "stage", stage, status, message, Instant.now());
        }

        public static TaskEvent progress(String taskId, String stage, String message) {
            return new TaskEvent(taskId, "progress", stage, null, message, Instant.now());
        }

        public static TaskEvent task(String taskId, String status) {
            return new TaskEvent(taskId, "task", null, status, null, Instant.now());
        }
    }

    public interface Listener {
        void onEvent(TaskEvent e);
    }

    private static final Logger log = LoggerFactory.getLogger(TaskEventPublisher.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public void publish(TaskEvent e) {
        for (Listener l : listeners) {
            try {
                l.onEvent(e);
            } catch (RuntimeException ex) {
                log.warn("任务事件监听器异常: {}", ex.getMessage());
            }
        }
    }
}
