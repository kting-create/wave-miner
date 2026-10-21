### Task 3: 任务模型扩展（engine_id/language/diarize）+ SchemaMigrator + 创建参数校验

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/pipeline/SchemaMigrator.java`
- Modify: `backend/src/main/java/com/waveminer/waveminer/pipeline/Task.java`、`pipeline/TaskRepository.java`、`pipeline/PipelineService.java`、`pipeline/TaskContext.java`、`pipeline/TaskRunner.java`、`api/TaskController.java`、`api/TaskViews.java`、现有测试 DDL（`PipelineServiceTest`、`TaskRunnerTest` 的 CREATE TABLE）
- Test: `TaskRepositoryTest` 追加断言、`PipelineServiceTest` 追加校验用例

**Interfaces:**
- Consumes: `EngineRegistry.get(id).info().languages()`（Task 2）
- Produces:
  - `Task` 增加字段 `String engineId; String language; boolean diarize;`（getter/setter，boolean 用 `isDiarize()`）；`TaskRepository.insert/find` 读写列 `engine_id TEXT`、`language TEXT`、`diarize INTEGER`
  - `SchemaMigrator implements ApplicationRunner`（`@Order(1)`）：`PRAGMA table_info(task)` 缺列则 `ALTER TABLE task ADD COLUMN engine_id TEXT / language TEXT / diarize INTEGER`（幂等，兼容旧库）
  - `CreateTaskCommand` 扩为 `record CreateTaskCommand(String url, MultipartFile file, List<StageType> stages, String language, String engineId, boolean diarize)`
  - 校验（PipelineService.validate 追加，仅勾选 TRANSCRIBE 时）：engineId 空白→默认 `minimax`；不在 `registry.all()` 内 → 400 "未知转写引擎: x"；language 空白/"auto"→"auto"；非 auto 且不在该引擎 `languages` → 400 "该引擎不支持的语言: x"
  - `TaskContext` 增加只读 `String engineId()`、`String language()`、`boolean diarize()`；保留原 5 参构造器（engineId=null, language="auto", diarize=false），新增 8 参构造器（TaskRunner 使用）
  - `TaskViews.TaskView` 增加字段 `String engineId, String language, boolean diarize`（放在 `errorMessage` 之后）；`POST /api/tasks` 新增 multipart 参数 `engineId`、`language`、`diarize`（"true"/"false"）

- [ ] **Step 1: TaskRepositoryTest 追加往返断言（先失败）**

```java
    @Test
    void engineFieldsRoundTrip() {
        Task t = newTask("t6e");
        t.setEngineId("minimax");
        t.setLanguage("zh");
        t.setDiarize(true);
        repo.insert(t);
        repo.insertStages("t6e", t.getStages());

        Task found = repo.find("t6e").orElseThrow();
        assertThat(found.getEngineId()).isEqualTo("minimax");
        assertThat(found.getLanguage()).isEqualTo("zh");
        assertThat(found.isDiarize()).isTrue();
    }
```

Run: `cd backend && ./mvnw -q test -Dtest=TaskRepositoryTest`
Expected: FAIL（编译错误，字段不存在）

- [ ] **Step 2: 实现 SchemaMigrator + 实体/仓储扩展**

`pipeline/SchemaMigrator.java`：

```java
package com.waveminer.waveminer.pipeline;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** SQLite 旧库列扩展：CREATE TABLE IF NOT EXISTS 不会补列，这里按需 ALTER。 */
@Component
@Order(1)
public class SchemaMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        List<String> columns = jdbc.query("PRAGMA table_info(task)", (rs, i) -> rs.getString("name"));
        if (columns.isEmpty()) return;
        addColumn(columns, "engine_id", "TEXT");
        addColumn(columns, "language", "TEXT");
        addColumn(columns, "diarize", "INTEGER");
    }

    private void addColumn(List<String> columns, String name, String type) {
        if (!columns.contains(name)) {
            jdbc.execute("ALTER TABLE task ADD COLUMN " + name + " " + type);
        }
    }
}
```

`Task.java` 追加字段（getter/setter 风格与现有一致）：

```java
    private String engineId;
    private String language;
    private boolean diarize;

    public String getEngineId() { return engineId; }
    public void setEngineId(String v) { this.engineId = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { this.language = v; }
    public boolean isDiarize() { return diarize; }
    public void setDiarize(boolean v) { this.diarize = v; }
```

`TaskRepository`：
- `insert` 的 INSERT 列与占位符各加 3 个：`engine_id,language,diarize`，值 `t.getEngineId(), t.getLanguage(), t.isDiarize() ? 1 : 0`
- `mapTask` 追加：`t.setEngineId(rs.getString("engine_id")); t.setLanguage(rs.getString("language")); t.setDiarize(rs.getInt("diarize") != 0);`

`TaskContext.java` 增加字段与构造器（原 5 参构造器委托新构造器）：

```java
    private final String engineId;
    private final String language;
    private final boolean diarize;

    public TaskContext(String taskId, Path dir, String sourceUrl, Path uploadedFile, AtomicBoolean cancel) {
        this(taskId, dir, sourceUrl, uploadedFile, cancel, null, "auto", false);
    }

    public TaskContext(String taskId, Path dir, String sourceUrl, Path uploadedFile, AtomicBoolean cancel,
                       String engineId, String language, boolean diarize) {
        this.taskId = taskId;
        this.dir = dir;
        this.sourceUrl = sourceUrl;
        this.uploadedFile = uploadedFile;
        this.cancel = cancel;
        this.engineId = engineId;
        this.language = language == null || language.isBlank() ? "auto" : language;
        this.diarize = diarize;
    }

    public String engineId() { return engineId; }

    public String language() { return language; }

    public boolean diarize() { return diarize; }
```

`TaskRunner.execute` 中 `TaskContext` 构造改为 8 参（追加末尾三项）：

```java
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
```

- [ ] **Step 3: PipelineService/TaskController/TaskViews 扩展**

`PipelineService`：
- `CreateTaskCommand` 改为 `record CreateTaskCommand(String url, MultipartFile file, List<StageType> stages, String language, String engineId, boolean diarize) {}`
- 构造器追加 `EngineRegistry registry` 参数（新第 4 位：`(TaskRepository repo, ArtifactStore artifacts, EngineRegistry registry, AppProperties props, Runner runner)`——注意现有测试按参数顺序更新）
- `create()` 中追加赋值：

```java
        t.setEngineId(cmd.engineId() == null || cmd.engineId().isBlank() ? "minimax" : cmd.engineId().trim());
        t.setLanguage(cmd.language() == null || cmd.language().isBlank() ? "auto" : cmd.language().trim());
        t.setDiarize(cmd.diarize());
```

- `validate()` 末尾追加：

```java
        if (stages.contains(StageType.TRANSCRIBE)) {
            String engineId = cmd.engineId() == null || cmd.engineId().isBlank() ? "minimax" : cmd.engineId().trim();
            TranscriptionEngine engine;
            try {
                engine = registry.get(engineId);
            } catch (EngineException e) {
                throw new IllegalArgumentException("未知转写引擎: " + engineId);
            }
            String lang = cmd.language() == null || cmd.language().isBlank() ? "auto" : cmd.language().trim();
            if (!"auto".equals(lang) && !engine.info().languages().contains(lang)) {
                throw new IllegalArgumentException("该引擎不支持的语言: " + lang);
            }
        }
```

（import `com.waveminer.waveminer.engine.EngineException`、`EngineRegistry`、`TranscriptionEngine`。）

`TaskController.create` 追加参数并传递：

```java
    public TaskViews.TaskView create(
            @RequestParam(required = false) String url,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam String stages,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String engineId,
            @RequestParam(defaultValue = "false") String diarize) {
        // stageList 解析保持不变
        Task t = pipeline.create(new PipelineService.CreateTaskCommand(
                url, file, stageList, language, engineId, Boolean.parseBoolean(diarize)));
        return toView(t);
    }
```

`TaskViews.TaskView` record 增加字段 `String engineId, String language, boolean diarize`；`toView` 传 `t.getEngineId(), t.getLanguage(), t.isDiarize()`。

**测试同步修改：**
- `PipelineServiceTest`：`url()/upload()` 辅助改为 `new CreateTaskCommand(url, null, stages, null, null, false)` 与 `new CreateTaskCommand(null, file, stages, null, null, false)`；直接构造处（`urlAndFileTogetherRejected`）同样补两参。构造 `PipelineService` 处注入含一个假引擎的 `EngineRegistry`：

```java
        TranscriptionEngine fake = Mockito.mock(TranscriptionEngine.class);
        Mockito.when(fake.id()).thenReturn("fake-engine");
        Mockito.when(fake.info()).thenReturn(new com.waveminer.waveminer.engine.EngineInfo(
                "fake-engine", "假引擎", 500, 1024, true, List.of("zh")));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<TranscriptionEngine> provider =
                Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        Mockito.when(provider.orderedStream()).thenReturn(java.util.stream.Stream.of(fake));
        service = new PipelineService(new TaskRepository(jdbc), new ArtifactStore(props),
                new com.waveminer.waveminer.engine.EngineRegistry(provider), props, runner);
```

- 追加两个校验用例：

```java
    @Test
    void unknownEngineRejected() {
        assertThatThrownBy(() -> service.create(new CreateTaskCommand(
                "https://e.com/a.mp4", null,
                List.of(StageType.DOWNLOAD, StageType.TRANSCRIBE), null, "no-such", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知转写引擎");
    }

    @Test
    void unsupportedLanguageRejected() {
        assertThatThrownBy(() -> service.create(new CreateTaskCommand(
                "https://e.com/a.mp4", null,
                List.of(StageType.DOWNLOAD, StageType.TRANSCRIBE), "xx", "fake-engine", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的语言");
    }
```

- `TaskRunnerTest` 与 `PipelineServiceTest` 的 CREATE TABLE DDL 在 `finished_at TEXT` 后追加 `, engine_id TEXT, language TEXT, diarize INTEGER`（两个文件各一处）。
- `TaskRunnerTest` 中 `new TaskContext(...)` 的既有用法不变（5 参构造器保留）。

- [ ] **Step 4: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS（旧库兼容由 SchemaMigrator 保证）

- [ ] **Step 5: 手工冒烟（旧库升级验证）**

```bash
cd backend && ./mvnw -q spring-boot:run &
sleep 8
curl -s -X POST http://127.0.0.1:8080/api/tasks -F url=https://example.com/a.mp4 -F stages=DOWNLOAD
kill %1
```

Expected: 返回 JSON 含 `"engineId":"minimax","language":"auto","diarize":false`；启动日志无 SQL 异常

- [ ] **Step 6: Commit**

```bash
git add backend && git commit -m "feat: 任务引擎/语言/说话人分离字段与创建校验"
```

---

