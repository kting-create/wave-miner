# Task 9 实现报告：SSE 进度接口 GET /api/tasks/{id}/events

- 状态：DONE
- 提交：`9319ec6` `feat: 任务进度 SSE 接口与心跳`（仅 3 个 backend 文件，+236 行）
- 测试：全量 `./mvnw.cmd test` → **119 tests, 0 failures, 0 errors, BUILD SUCCESS**（T1-T8 的 117 + 本任务新增 2）

## 做了什么

### 新增文件（全部按 task-9-brief 实现）
1. `backend/src/main/java/com/waveminer/waveminer/api/TaskSseRegistry.java`（105 行）
   - `@Component`，构造时向 `TaskEventPublisher` 注册 `Listener`。
   - `subscribe(taskId)`：`SseEmitter(0L)` 不超时；登记到 `ConcurrentHashMap<String, List<SseEmitter>>`；注册 `onCompletion/onTimeout/onError` 清理回调；同步发送首事件 `event:"progress"` + `{"taskId":"..","type":"connected"}`（Jackson 3 `tools.jackson.databind.ObjectMapper` 序列化）；发送失败则移除并 complete。
   - `onEvent(e)`：向该 taskId 所有 emitter 发 `event:"progress"` + TaskEvent JSON（taskId/type/stage/status/message/at 六字段）；`type=="task"` 且 status ∈ {COMPLETED, FAILED, CANCELLED} 时发送后 `complete()` 并移除；发送异常（IOException/IllegalStateException）时移除 + complete。
   - 心跳：单守护线程 `ScheduledExecutorService`（`sse-heartbeat`，15s 周期）向全部 emitter 发注释 `":hb"`，失败 → 移除。
   - 包级 `int emitterCount(String taskId)` 供测试断言（HANDOFF §2 授权的备选断言入口）。
2. `backend/src/main/java/com/waveminer/waveminer/api/TaskSseController.java`（27 行）
   - `GET /api/tasks/{id}/events`（produces `TEXT_EVENT_STREAM`）：`repo.find(id)`（TaskRepository 实际方法名是 `find` 而非 brief 手稿里的 `findById`，以实际代码为准）不存在 → `NoSuchElementException("任务不存在")` → 由既有 `ApiExceptionHandler` 映射 404；存在 → `registry.subscribe(id)`。
3. `backend/src/test/java/com/waveminer/waveminer/api/TaskSseApiTest.java`（104 行）
   - `unknownTaskReturns404`：`/api/tasks/no-such-id/events` → 404。
   - `subscribesAndReceivesEventsUntilTerminal`：API 建真实任务（Mock ffmpeg，同 TaskApiTest 模式）→ 等任务 COMPLETED 后订阅 SSE（断言 asyncStarted）→ 手动 `publish(progress)` + `publish(task COMPLETED)` → 断言响应体含 `event:progress`、`进度你好`、`"type":"connected"`、`"type":"task"`，且 `registry.emitterCount(id)==0`。

### TDD 过程
1. 先写测试 → 编译失败（红，TaskSseRegistry/TaskSseController 不存在 + 一处 Spring 7 API 差异）→ 实现后全绿。
2. 全量回归 119/119 绿。

## 对 brief 的必要偏离（均有依据）

1. **`AutoConfigureMockMvc` 包名**：brief 手稿是 `org.springframework.boot.test.autoconfigure.AutoConfigureMockMvc`，Spring Boot 4 实际为 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`（handoff 已裁定；与 TaskApiTest 一致）。
2. **测试加了 `spring.datasource.type=DriverManagerDataSource`**：@SpringBootTest 涉及 SQLite 临时库，Windows 上释放文件句柄必需（handoff 已裁定；T1-T8 惯例）。
3. **测试加了 `@MockitoBean FfmpegService/YtDlpService` + 等任务 COMPLETED 后再订阅**：不 mock 的话后台管线会异步失败并发布 `task FAILED` 事件，与手动发布的终态事件竞争 emitter 的 complete 时机，测试会闪断；等待后台管线静默后再订阅 + 手动发事件，完全确定。
4. **等待信号从 `isAsyncComplete()` 改为 dispatch latch**：Spring Framework 7.0.9 移除了 `MockHttpServletRequest.isAsyncComplete()`（编译期确认）。字节码考古结论：Spring 7 的 SSE 基于 DeferredResult 实现，`emitter.complete()` → `DefaultSseEmitterHandler.complete()` → `outputMessage.flush()` + `deferredResult.setResult(null)` → `WebAsyncManager` → `MockAsyncContext.dispatch()`（只触发 dispatch handler，**不**翻转 `isAsyncStarted`，也不调 `complete()`）。因此改为 Awaitility 轮询 `sse.getAsyncResult(50)`（内部等 TestDispatcherServlet 注册的 dispatch CountDownLatch，字节码确认）+ `asyncDispatch(sse)` 重放请求取响应体。该路径经实测可用，语义与 brief 的原等待等价（终态 complete → dispatch → 可取响应）。
5. **响应体按 UTF-8 手工解码**：Spring 7 响应 Content-Type 为 `text/event-stream`（无 charset），而 SSE 规范强制 UTF-8；MockMvc 的 `getContentAsString()` 按 ISO-8859-1 解码导致中文断言失败。改用 `getContentAsByteArray()` + UTF-8 解码，纯测试侧修正，生产行为（SSE 字节流 UTF-8）不受影响。
6. **Controller 无 UUID 格式校验**：brief 测试期望 `/api/tasks/no-such-id/events` 返回 404（而非 TaskController 的 400"非法的任务 ID"），故直接 `repo.find` → 404，与 brief 一致。

## 自查发现
- `git diff HEAD~1` 显示的 progress.md 改动是任务开始前工作区已有的 SDD 文件改动，**不在本次 commit 内**（commit 仅 3 个 backend 文件）；`task-9-brief.md` 为 orchestrator 的未跟踪工作区文件，同样未纳入。
- 心跳线程为守护线程、无显式 shutdown，随 JVM 退出（brief 如此设计，未改动）。
- `emitterCount()` 虽最终未被依赖为主断言路径（asyncDispatch 实测可用），仍保留：终态移除断言用到了它，且作为 MockMvc 异步不稳时的备选出口。

## 复现
```
cd backend; ./mvnw.cmd test -Dtest=TaskSseApiTest   # 2 tests green
cd backend; ./mvnw.cmd test                          # 119 tests green
```
