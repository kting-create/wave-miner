### Task 9: SSE 进度接口 GET /api/tasks/{id}/events

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/api/TaskSseRegistry.java`、`api/TaskSseController.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/api/TaskSseApiTest.java`

**Interfaces:**
- Consumes: `TaskEventPublisher`（Task 7）、`TaskRepository`、`tools.jackson.databind.ObjectMapper`
- Produces:
  - `api/TaskSseRegistry`（@Component，构造订阅 `TaskEventPublisher`）：
    - `SseEmitter subscribe(String taskId)`：创建 `new SseEmitter(0L)`（不超时），登记到 `ConcurrentHashMap<String, List<SseEmitter>>`；注册 `onCompletion/onTimeout/onError` 回调移除；同步发送首事件 `event:"progress"`，data=`{"taskId":"..","type":"connected",...}`；发送失败则移除并 `complete`
    - `void onEvent(TaskEvent e)`（publisher Listener）：向该 taskId 的所有 emitter 发送 `event:"progress"`、data=TaskEvent 的 JSON（ObjectMapper 序列化，字段 taskId/type/stage/status/message/at）；`type=="task"` 且 status ∈ COMPLETED/FAILED/CANCELLED 时发送后 `complete` 并移除（前端依赖此行为结束流）
    - 心跳：单守护线程 `ScheduledExecutorService`（15s 周期）向全部 emitter 发注释 `":hb\n\n"`，IOException → complete+移除
  - `GET /api/tasks/{id}/events`（TaskSseController）：任务不存在 → `NoSuchElementException("任务不存在")`（404）；存在 → 返回 `registry.subscribe(id)`（produces = `MediaType.TEXT_EVENT_STREAM_VALUE`）
  - 鉴权沿用 `AccessTokenInterceptor`（`/api/**`，EventSource 用 `?token=`）

- [ ] **Step 1: 写测试（先失败）**

`api/TaskSseApiTest.java`：

```java
package com.waveminer.waveminer.api;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.waveminer.waveminer.pipeline.TaskEventPublisher;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TaskSseApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TaskEventPublisher publisher;

    @Test
    void unknownTaskReturns404() throws Exception {
        mvc.perform(get("/api/tasks/no-such-id/events")).andExpect(status().isNotFound());
    }

    @Test
    void subscribesAndReceivesEventsUntilTerminal() throws Exception {
        // 准备一个真实任务（借 SettingApiTest 同款环境，直接调 API 建任务最简单）
        MvcResult created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/tasks")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "clip.mp4", "video/mp4", new byte[]{1, 2, 3, 4}))
                        .param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isOk()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        MvcResult sse = mvc.perform(get("/api/tasks/" + id + "/events")
                        .accept("text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        publisher.publish(TaskEventPublisher.TaskEvent.progress(id, "TRANSCRIBE", "进度你好"));
        publisher.publish(TaskEventPublisher.TaskEvent.task(id, "COMPLETED"));

        await().atMost(5, TimeUnit.SECONDS).until(() -> sse.getRequest().isAsyncComplete());
        String body = mvc.perform(asyncDispatch(sse)).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("event:progress");
        org.assertj.core.api.Assertions.assertThat(body).contains("进度你好");
        org.assertj.core.api.Assertions.assertThat(body).contains("\"type\":\"connected\"");
        org.assertj.core.api.Assertions.assertThat(body).contains("\"type\":\"task\"");
    }
}
```

> **实现提示**：MockMvc 下 SseEmitter 的首事件与后续事件都会缓存在异步响应里；终态 `complete()` 触发 async dispatch。若 `asyncComplete` 等待不稳定，可将终态断言放宽为仅包含 connected+progress 事件、并验证 registry 的移除逻辑用独立单元测试（直接调 `registry.onEvent(...)` 后 `registry.emitterCount(taskId)==0`，为此在 TaskSseRegistry 提供包级 `int emitterCount(String taskId)`）。

Run: `cd backend && ./mvnw -q test -Dtest=TaskSseApiTest`
Expected: FAIL，编译错误

- [ ] **Step 2: 实现 TaskSseRegistry**

`api/TaskSseRegistry.java`：

```java
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
```

- [ ] **Step 3: 实现 TaskSseController**

`api/TaskSseController.java`：

```java
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
```

- [ ] **Step 4: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: 任务进度 SSE 接口与心跳"
```

---
