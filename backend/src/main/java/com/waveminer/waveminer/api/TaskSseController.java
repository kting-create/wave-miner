package com.waveminer.waveminer.api;

import com.waveminer.waveminer.pipeline.TaskRepository;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class TaskSseController {

    private final TaskRepository repo;
    private final TaskSseRegistry registry;

    public TaskSseController(TaskRepository repo, TaskSseRegistry registry) {
        this.repo = repo;
        this.registry = registry;
    }

    @GetMapping(value = "/api/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        repo.find(id).orElseThrow(() -> new NoSuchElementException("任务不存在"));
        return registry.subscribe(id);
    }
}
