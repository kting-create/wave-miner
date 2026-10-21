# wave-miner 计划 1（M1+M2）：任务骨架 + 下载 + 音频提取 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 wave-miner 后端任务系统（SQLite + 虚拟线程流水线）与最小可用前端，交付「视频下载 + 音频提取」两个可用功能（含任务历史、产物下载、在线预览）。

**Architecture:** 单体 Spring Boot 应用；任务由 `PipelineService` 创建并校验组合，`TaskRunner` 用 JDK21 虚拟线程按顺序执行 Stage 链（Semaphore 限并发）；外部工具（yt-dlp/ffmpeg）经 `ProcessRunner` 调用；产物落盘 `data/tasks/{id}/`，任务状态存 SQLite；前端 Vue 3 SPA 轮询进度（SSE 属计划 2）。

**Tech Stack:** Java 21、Spring Boot 4.1.x、sqlite-jdbc、Vue 3 + Vite + TypeScript + Element Plus、ffmpeg/ffprobe、yt-dlp。

**Spec:** `docs/superpowers/specs/2026-10-21-wave-miner-design.md`（本计划实现其 §2/§3/§4/§6/§7/§8/§10/§12-M1/M2；转写引擎 §5 与 SSE/时间轴 §9 属计划 2）

## Global Constraints

- Java 21、Spring Boot 4.1.x（start.spring.io 当前默认 4.1.1；若该版本号被拒绝，从 https://start.spring.io 元数据取最新 4.1.x）。规格原定 3.x，已更新为 4.x（3.x OSS 支持已于 2026-06 结束）。
- 不使用 Lombok；实体用普通类+getter/setter，DTO 用 record。
- SQLite 驱动 `org.xerial:sqlite-jdbc:3.46.1.0`（Spring Boot BOM 不管理此依赖，须显式锁版本）。
- 服务默认绑定 `127.0.0.1`；用户可见错误信息一律中文。
- 测试不依赖网络与真实 ffmpeg/yt-dlp（外部工具 bean 可替换/可 Mockito）；本机为 macOS (darwin)。
- 每个任务以 conventional commit 收尾（`feat:`/`test:`/`chore:`），一次提交对应一个可运行状态。
- 所有新文件 UTF-8；前端组件用 `<script setup lang="ts">`。
- 通用验证命令：`cd backend && ./mvnw -q test`；`cd frontend && npm run test && npm run build`。

---

### Task 1: 后端项目引导（Spring Boot + SQLite + 配置骨架）

**Files:**
- Create: `backend/`（start.spring.io 生成）、`.gitignore`
- Modify: `backend/pom.xml`、`backend/src/main/resources/application.yml`、启动类与测试类

**Interfaces:**
- Produces: `AppProperties`（`dataDir/maxConcurrentTasks/ffmpegPath/ffprobePath/ytDlpPath/processTimeoutSeconds/accessToken` + `Duration processTimeout()`）；后续所有任务依赖。

- [ ] **Step 1: 用 start.spring.io 生成项目**

```bash
curl -sf https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d bootVersion=4.1.1 \
  -d baseDir=backend -d groupId=com.waveminer -d artifactId=wave-miner \
  -d name=WaveMiner -d packageName=com.waveminer.waveminer \
  -d javaVersion=21 -d dependencies=web,jdbc,validation -o /tmp/wm.zip \
  && unzip -qo /tmp/wm.zip -d .
```

若 `bootVersion=4.1.1` 被拒绝：`curl -sf https://start.spring.io/metadata/client -H "Accept: application/json"` 查看 `bootVersion` 默认值，用该值重试。

- [ ] **Step 2: 根目录 .gitignore**

```gitignore
backend/target/
frontend/node_modules/
frontend/dist/
data/
.DS_Store
*.log
```

- [ ] **Step 3: pom.xml 增加 sqlite 依赖**

`backend/pom.xml` 的 `<dependencies>` 中追加：

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.46.1.0</version>
</dependency>
```

- [ ] **Step 4: application.yml 全量替换**

```yaml
server:
  address: 127.0.0.1
  port: 8080
spring:
  application:
    name: wave-miner
  datasource:
    url: jdbc:sqlite:${app.data-dir}/wave-miner.db
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
  sql:
    init:
      mode: always
  servlet:
    multipart:
      max-file-size: 2GB
      max-request-size: 2100MB
  threads:
    virtual:
      enabled: true
app:
  data-dir: ./data
  max-concurrent-tasks: 2
  ffmpeg-path: ffmpeg
  ffprobe-path: ffprobe
  yt-dlp-path: yt-dlp
  process-timeout-seconds: 1800
  access-token: ""
```

- [ ] **Step 5: AppProperties**

`backend/src/main/java/com/waveminer/waveminer/config/AppProperties.java`：

```java
package com.waveminer.waveminer.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String dataDir = "./data";
    private int maxConcurrentTasks = 2;
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";
    private String ytDlpPath = "yt-dlp";
    private long processTimeoutSeconds = 1800;
    private String accessToken = "";

    public Duration processTimeout() { return Duration.ofSeconds(processTimeoutSeconds); }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(int v) { this.maxConcurrentTasks = v; }
    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String v) { this.ffmpegPath = v; }
    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String v) { this.ffprobePath = v; }
    public String getYtDlpPath() { return ytDlpPath; }
    public void setYtDlpPath(String v) { this.ytDlpPath = v; }
    public long getProcessTimeoutSeconds() { return processTimeoutSeconds; }
    public void setProcessTimeoutSeconds(long v) { this.processTimeoutSeconds = v; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String v) { this.accessToken = v; }
}
```

- [ ] **Step 6: 启动类启用配置属性**

`backend/src/main/java/com/waveminer/waveminer/WaveMinerApplication.java`：

```java
package com.waveminer.waveminer;

import com.waveminer.waveminer.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class WaveMinerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WaveMinerApplication.class, args);
    }
}
```

- [ ] **Step 7: 上下文冒烟测试**

`backend/src/test/java/com/waveminer/waveminer/WaveMinerApplicationTests.java`（initializr 已生成，保持/改为）：

```java
package com.waveminer.waveminer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WaveMinerApplicationTests {
    @Test
    void contextLoads() {}
}
```

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS（SQLite 库文件生成于 `data/`，已被 gitignore）

- [ ] **Step 8: Commit**

```bash
git add .gitignore backend && git commit -m "chore: Spring Boot 4.1 + SQLite 项目骨架"
```

---

### Task 2: 领域模型 + SQLite 仓储

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/pipeline/`（TaskStatus/StageType/StageStatus/SourceType/Task/TaskStage/TaskRepository）
- Create: `backend/src/main/resources/schema.sql`
- Test: `backend/src/test/java/com/waveminer/waveminer/pipeline/TaskRepositoryTest.java`

**Interfaces:**
- Produces（后续任务全部依赖，签名精确如下）:
  - 枚举 `TaskStatus{PENDING,RUNNING,COMPLETED,FAILED,CANCELLED}`、`StageType{DOWNLOAD,AUDIO_EXTRACT,TRANSCRIBE}`、`StageStatus{PENDING,RUNNING,DONE,SKIPPED,FAILED}`、`SourceType{URL,UPLOAD}`
  - `Task`：`getId/getSourceType/getSourceRef/getTitle/getStages(List<StageType>)/getStatus/getErrorMessage/getCreatedAt/getStartedAt/getFinishedAt` + 对应 setter
  - `TaskStage`：`getTaskId/getStageType/getStatus/getDetail/getArtifactPath` + setter
  - `TaskRepository`：`Task insert(Task)`、`void insertStages(String taskId, List<StageType>)`、`Optional<Task> find(String id)`、`List<TaskStage> stagesOf(String id)`、`List<Task> list(String status, int page, int size)`、`long count(String status)`、`void updateTaskStatus(String id, TaskStatus s, String errorMessage)`、`void updateStartedAt(String id, Instant t)`、`void updateFinishedAt(String id, Instant t)`、`void updateStage(String taskId, StageType type, StageStatus s, String detail, String artifactPath)`、`void resetStagesForRetry(String id)`、`void resetTaskForRetry(String id)`、`void delete(String id)`、`void markStaleRunningAsFailed(String message)`

- [ ] **Step 1: schema.sql**

`backend/src/main/resources/schema.sql`：

```sql
CREATE TABLE IF NOT EXISTS task (
    id            TEXT PRIMARY KEY,
    source_type   TEXT NOT NULL,
    source_ref    TEXT,
    title         TEXT NOT NULL,
    stages        TEXT NOT NULL,
    status        TEXT NOT NULL,
    error_message TEXT,
    created_at    TEXT NOT NULL,
    started_at    TEXT,
    finished_at   TEXT
);
CREATE TABLE IF NOT EXISTS task_stage (
    task_id       TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    stage_type    TEXT NOT NULL,
    status        TEXT NOT NULL,
    detail        TEXT,
    artifact_path TEXT,
    PRIMARY KEY (task_id, stage_type)
);
```

- [ ] **Step 2: 枚举（4 个文件）**

`pipeline/TaskStatus.java`：`package com.waveminer.waveminer.pipeline; public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }`
`pipeline/StageType.java`：`package com.waveminer.waveminer.pipeline; public enum StageType { DOWNLOAD, AUDIO_EXTRACT, TRANSCRIBE }`
`pipeline/StageStatus.java`：`package com.waveminer.waveminer.pipeline; public enum StageStatus { PENDING, RUNNING, DONE, SKIPPED, FAILED }`
`pipeline/SourceType.java`：`package com.waveminer.waveminer.pipeline; public enum SourceType { URL, UPLOAD }`

- [ ] **Step 3: Task / TaskStage 实体**

`pipeline/Task.java`：普通类，字段 `String id; SourceType sourceType; String sourceRef; String title; List<StageType> stages; TaskStatus status; String errorMessage; Instant createdAt; Instant startedAt; Instant finishedAt;`，全部提供 getter/setter（`java.time.Instant`、`java.util.List`）。

`pipeline/TaskStage.java`：字段 `String taskId; StageType stageType; StageStatus status; String detail; String artifactPath;`，全部提供 getter/setter。

- [ ] **Step 4: 写仓储测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/pipeline/TaskRepositoryTest.java`：

```java
package com.waveminer.waveminer.pipeline;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TaskRepositoryTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
    }

    @Autowired TaskRepository repo;

    private Task newTask(String id) {
        Task t = new Task();
        t.setId(id);
        t.setSourceType(SourceType.URL);
        t.setSourceRef("https://example.com/a.mp4");
        t.setTitle("a.mp4");
        t.setStages(List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT));
        t.setStatus(TaskStatus.PENDING);
        t.setCreatedAt(Instant.parse("2026-10-21T00:00:00Z"));
        return t;
    }

    @Test
    void insertAndFind() {
        repo.insert(newTask("t1"));
        repo.insertStages("t1", List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT));

        Optional<Task> found = repo.find("t1");
        assertThat(found).isPresent();
        Task t = found.get();
        assertThat(t.getSourceType()).isEqualTo(SourceType.URL);
        assertThat(t.getStages()).containsExactly(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT);
        assertThat(t.getCreatedAt()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));

        List<TaskStage> stages = repo.stagesOf("t1");
        assertThat(stages).hasSize(2);
        assertThat(stages.get(0).getStageType()).isEqualTo(StageType.DOWNLOAD);
        assertThat(stages.get(0).getStatus()).isEqualTo(StageStatus.PENDING);
    }

    @Test
    void updateStatusAndStages() {
        repo.insert(newTask("t2"));
        repo.insertStages("t2", List.of(StageType.DOWNLOAD));

        repo.updateTaskStatus("t2", TaskStatus.RUNNING, null);
        repo.updateStartedAt("t2", Instant.now());
        repo.updateStage("t2", StageType.DOWNLOAD, StageStatus.DONE, null, "source.mp4");

        assertThat(repo.find("t2").orElseThrow().getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(repo.stagesOf("t2").get(0).getStatus()).isEqualTo(StageStatus.DONE);
        assertThat(repo.stagesOf("t2").get(0).getArtifactPath()).isEqualTo("source.mp4");

        repo.updateTaskStatus("t2", TaskStatus.FAILED, "下载失败");
        assertThat(repo.find("t2").orElseThrow().getErrorMessage()).isEqualTo("下载失败");
    }

    @Test
    void listPagingAndFilter() {
        repo.insert(newTask("t3a"));
        repo.insert(newTask("t3b"));
        repo.updateTaskStatus("t3b", TaskStatus.COMPLETED, null);

        // 同类其他测试会插入数据，这里只做非绝对数量断言
        assertThat(repo.list(null, 0, 10)).extracting(Task::getId).contains("t3a", "t3b");
        assertThat(repo.list("COMPLETED", 0, 10)).extracting(Task::getId).contains("t3b");
        assertThat(repo.list("COMPLETED", 0, 10)).extracting(Task::getId).doesNotContain("t3a");
        assertThat(repo.count(null)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void retryResetAndDelete() {
        repo.insert(newTask("t4"));
        repo.insertStages("t4", List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT));
        repo.updateStage("t4", StageType.DOWNLOAD, StageStatus.DONE, null, "source.mp4");
        repo.updateStage("t4", StageType.AUDIO_EXTRACT, StageStatus.FAILED, "boom", null);
        repo.updateTaskStatus("t4", TaskStatus.FAILED, "boom");

        repo.resetTaskForRetry("t4");
        repo.resetStagesForRetry("t4");

        Task t = repo.find("t4").orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.getErrorMessage()).isNull();
        assertThat(repo.stagesOf("t4").get(0).getStatus()).isEqualTo(StageStatus.DONE);
        assertThat(repo.stagesOf("t4").get(1).getStatus()).isEqualTo(StageStatus.PENDING);

        repo.delete("t4");
        assertThat(repo.find("t4")).isEmpty();
        assertThat(repo.stagesOf("t4")).isEmpty();
    }

    @Test
    void staleRecovery() {
        repo.insert(newTask("t5"));
        repo.insertStages("t5", List.of(StageType.DOWNLOAD));
        repo.updateTaskStatus("t5", TaskStatus.RUNNING, null);
        repo.updateStage("t5", StageType.DOWNLOAD, StageStatus.RUNNING, null, null);

        repo.markStaleRunningAsFailed("应用重启导致中断，可重试");

        Task t = repo.find("t5").orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(t.getErrorMessage()).contains("应用重启");
        assertThat(repo.stagesOf("t5").get(0).getStatus()).isEqualTo(StageStatus.FAILED);
    }

    @Test
    void findMissing() {
        assertThat(repo.find("nope")).isEmpty();
    }
}
```

Run: `cd backend && ./mvnw -q test`
Expected: FAIL，编译错误 `TaskRepository` 不存在

- [ ] **Step 5: 实现 TaskRepository**

`pipeline/TaskRepository.java`：

```java
package com.waveminer.waveminer.pipeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TaskRepository {

    private static final RowMapper<Task> TASK_MAPPER = TaskRepository::mapTask;

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Task insert(Task t) {
        jdbc.update("INSERT INTO task(id,source_type,source_ref,title,stages,status,error_message,created_at,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                t.getId(), t.getSourceType().name(), t.getSourceRef(), t.getTitle(),
                joinStages(t.getStages()), t.getStatus().name(), t.getErrorMessage(),
                ts(t.getCreatedAt()), ts(t.getStartedAt()), ts(t.getFinishedAt()));
        return t;
    }

    @Transactional
    public void insertStages(String taskId, List<StageType> stages) {
        for (StageType s : stages) {
            jdbc.update("INSERT INTO task_stage(task_id,stage_type,status) VALUES(?,?,'PENDING')",
                    taskId, s.name());
        }
    }

    public Optional<Task> find(String id) {
        List<Task> rows = jdbc.query("SELECT * FROM task WHERE id = ?", TASK_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<TaskStage> stagesOf(String taskId) {
        return jdbc.query("SELECT * FROM task_stage WHERE task_id = ? ORDER BY rowid",
                TaskRepository::mapStage, taskId);
    }

    public List<Task> list(String status, int page, int size) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM task ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?",
                    TASK_MAPPER, size, page * size);
        }
        return jdbc.query("SELECT * FROM task WHERE status = ? ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?",
                TASK_MAPPER, status, size, page * size);
    }

    public long count(String status) {
        if (status == null || status.isBlank()) {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM task", Long.class);
            return n == null ? 0 : n;
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    @Transactional
    public void updateTaskStatus(String id, TaskStatus status, String errorMessage) {
        jdbc.update("UPDATE task SET status = ?, error_message = ? WHERE id = ?",
                status.name(), errorMessage, id);
    }

    @Transactional
    public void updateStartedAt(String id, Instant t) {
        jdbc.update("UPDATE task SET started_at = ? WHERE id = ?", ts(t), id);
    }

    @Transactional
    public void updateFinishedAt(String id, Instant t) {
        jdbc.update("UPDATE task SET finished_at = ? WHERE id = ?", ts(t), id);
    }

    @Transactional
    public void updateStage(String taskId, StageType type, StageStatus status, String detail, String artifactPath) {
        jdbc.update("UPDATE task_stage SET status = ?, detail = ?, artifact_path = ? WHERE task_id = ? AND stage_type = ?",
                status.name(), detail, artifactPath, taskId, type.name());
    }

    @Transactional
    public void resetTaskForRetry(String id) {
        jdbc.update("UPDATE task SET status = 'PENDING', error_message = NULL, started_at = NULL, finished_at = NULL WHERE id = ?", id);
    }

    @Transactional
    public void resetStagesForRetry(String id) {
        jdbc.update("UPDATE task_stage SET status = 'PENDING', detail = NULL WHERE task_id = ? AND status != 'DONE'", id);
    }

    @Transactional
    public void delete(String id) {
        jdbc.update("DELETE FROM task_stage WHERE task_id = ?", id);
        jdbc.update("DELETE FROM task WHERE id = ?", id);
    }

    @Transactional
    public void markStaleRunningAsFailed(String message) {
        jdbc.update("UPDATE task SET status = 'FAILED', error_message = ?, finished_at = ? WHERE status IN ('PENDING','RUNNING')",
                message, ts(Instant.now()));
        jdbc.update("UPDATE task_stage SET status = 'FAILED', detail = ? WHERE status IN ('PENDING','RUNNING')", message);
    }

    private static Task mapTask(ResultSet rs, int i) throws SQLException {
        Task t = new Task();
        t.setId(rs.getString("id"));
        t.setSourceType(SourceType.valueOf(rs.getString("source_type")));
        t.setSourceRef(rs.getString("source_ref"));
        t.setTitle(rs.getString("title"));
        t.setStages(Arrays.stream(rs.getString("stages").split(","))
                .filter(s -> !s.isBlank())
                .map(StageType::valueOf)
                .collect(Collectors.toList()));
        t.setStatus(TaskStatus.valueOf(rs.getString("status")));
        t.setErrorMessage(rs.getString("error_message"));
        t.setCreatedAt(parseTs(rs.getString("created_at")));
        t.setStartedAt(parseTs(rs.getString("started_at")));
        t.setFinishedAt(parseTs(rs.getString("finished_at")));
        return t;
    }

    private static TaskStage mapStage(ResultSet rs, int i) throws SQLException {
        TaskStage s = new TaskStage();
        s.setTaskId(rs.getString("task_id"));
        s.setStageType(StageType.valueOf(rs.getString("stage_type")));
        s.setStatus(StageStatus.valueOf(rs.getString("status")));
        s.setDetail(rs.getString("detail"));
        s.setArtifactPath(rs.getString("artifact_path"));
        return s;
    }

    private static String joinStages(List<StageType> stages) {
        return stages.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static String ts(Instant t) { return t == null ? null : t.toString(); }

    private static Instant parseTs(String s) { return s == null ? null : Instant.parse(s); }
}
```

- [ ] **Step 6: 运行测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS（全部 PASS）

- [ ] **Step 7: Commit**

```bash
git add backend && git commit -m "feat: 任务领域模型与 SQLite 仓储"
```

---

### Task 3: MediaTypes + ArtifactStore（产物存储与路径安全）

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/media/MediaTypes.java`、`store/ArtifactStore.java`、`store/ArtifactNotFoundException.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/store/ArtifactStoreTest.java`

**Interfaces:**
- Consumes: `AppProperties`（Task 1）
- Produces:
  - `MediaTypes`：`Set<String> VIDEO_EXTENSIONS`、`Set<String> AUDIO_EXTENSIONS`、`Set<String> ALL_EXTENSIONS`、`static String extension(String filename)`（小写无点，无扩展名返回 ""）、`static boolean isVideo(String filename)`、`static boolean isMedia(String filename)`
  - `ArtifactStore`：`Path taskDir(String taskId)`（自动建目录）、`static Optional<Path> findSourceFile(Path dir)`（`source.<ext>`）、`List<ArtifactInfo> listArtifacts(String taskId)`、`boolean isAllowedArtifactName(String name)`
  - `record ArtifactInfo(String name, long sizeBytes)`（定义在 `store` 包）
  - `ArtifactNotFoundException extends RuntimeException`

- [ ] **Step 1: 写测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/store/ArtifactStoreTest.java`：

```java
package com.waveminer.waveminer.store;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.media.MediaTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStoreTest {

    @TempDir
    Path tmp;

    private ArtifactStore store() {
        AppProperties props = new AppProperties();
        props.setDataDir(tmp.resolve("data").toString());
        return new ArtifactStore(props);
    }

    @Test
    void taskDirCreatedLazily() {
        ArtifactStore s = store();
        Path dir = s.taskDir("abc");
        assertThat(Files.isDirectory(dir)).isTrue();
        assertThat(dir.getFileName().toString()).isEqualTo("abc");
    }

    @Test
    void rejectsTraversalNames() {
        ArtifactStore s = store();
        assertThat(s.isAllowedArtifactName("audio.m4a")).isTrue();
        assertThat(s.isAllowedArtifactName("source.mp4")).isTrue();
        assertThat(s.isAllowedArtifactName("source.mkv")).isTrue();
        assertThat(s.isAllowedArtifactName("../t2/source.mp4")).isFalse();
        assertThat(s.isAllowedArtifactName("sub/transcript.json")).isFalse();
        assertThat(s.isAllowedArtifactName("source..mp4")).isFalse();
        assertThat(s.isAllowedArtifactName("evil.exe")).isFalse();
    }

    @Test
    void findsSourceFileAndListsArtifacts() throws Exception {
        ArtifactStore s = store();
        Path dir = s.taskDir("abc");
        Files.writeString(dir.resolve("source.mp4"), "video");
        Files.writeString(dir.resolve("audio.m4a"), "audio");
        Files.writeString(dir.resolve("notes.txt"), "ignored");

        assertThat(ArtifactStore.findSourceFile(dir)).hasValueSatisfying(p ->
                assertThat(p.getFileName().toString()).isEqualTo("source.mp4"));

        List<ArtifactInfo> artifacts = s.listArtifacts("abc");
        assertThat(artifacts).extracting(ArtifactInfo::name)
                .containsExactlyInAnyOrder("source.mp4", "audio.m4a");
        assertThat(artifacts).allSatisfy(a -> assertThat(a.sizeBytes()).isGreaterThan(0));
    }

    @Test
    void extensionHelpers() {
        assertThat(MediaTypes.extension("A.MP4")).isEqualTo("mp4");
        assertThat(MediaTypes.extension("noext")).isEmpty();
        assertThat(MediaTypes.isVideo("x.mov")).isTrue();
        assertThat(MediaTypes.isVideo("x.mp3")).isFalse();
        assertThat(MediaTypes.isMedia("x.flac")).isTrue();
        assertThat(MediaTypes.isMedia("x.txt")).isFalse();
    }
}
```

Run: `cd backend && ./mvnw -q test`
Expected: FAIL，编译错误（类不存在）

- [ ] **Step 2: 实现 MediaTypes**

`media/MediaTypes.java`：

```java
package com.waveminer.waveminer.media;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class MediaTypes {

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "mov", "webm", "avi", "flv", "ts", "m4v");
    public static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "wav", "aac", "flac", "opus", "ogg", "aiff");
    public static final Set<String> ALL_EXTENSIONS;

    static {
        Set<String> all = new HashSet<>(VIDEO_EXTENSIONS);
        all.addAll(AUDIO_EXTENSIONS);
        ALL_EXTENSIONS = Set.copyOf(all);
    }

    private MediaTypes() {}

    public static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isVideo(String filename) {
        return VIDEO_EXTENSIONS.contains(extension(filename));
    }

    public static boolean isMedia(String filename) {
        return ALL_EXTENSIONS.contains(extension(filename));
    }
}
```

- [ ] **Step 3: 实现 ArtifactStore 与异常**

`store/ArtifactNotFoundException.java`：

```java
package com.waveminer.waveminer.store;

public class ArtifactNotFoundException extends RuntimeException {
    public ArtifactNotFoundException(String message) { super(message); }
}
```

`store/ArtifactStore.java`：

```java
package com.waveminer.waveminer.store;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.media.MediaTypes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class ArtifactStore {

    private final Path root;

    public ArtifactStore(AppProperties props) {
        this.root = Path.of(props.getDataDir()).toAbsolutePath().normalize();
    }

    public Path taskDir(String taskId) {
        try {
            return Files.createDirectories(root.resolve(taskId));
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建任务目录: " + taskId, e);
        }
    }

    public static Optional<Path> findSourceFile(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("source."))
                    .filter(p -> MediaTypes.isMedia(p.getFileName().toString()))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean isAllowedArtifactName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        if (name.equals("audio.m4a")) return true;
        if (name.startsWith("source.")) {
            String ext = name.substring("source.".length());
            return !ext.isBlank() && MediaTypes.ALL_EXTENSIONS.contains(ext.toLowerCase());
        }
        return false;
    }

    public List<ArtifactInfo> listArtifacts(String taskId) {
        Path dir = taskDir(taskId);
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(this::isAllowedArtifactName)
                    .map(name -> new ArtifactInfo(name, sizeOf(dir.resolve(name))))
                    .sorted(Comparator.comparing(ArtifactInfo::name))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
```

- [ ] **Step 4: 运行测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: 产物存储 ArtifactStore 与媒体类型工具"
```

---

### Task 4: ProcessRunner（外部进程调用封装）

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/media/ProcessRunner.java`、`media/ProcessResult.java`、`media/ProcessFailedException.java`、`media/ProcessTimeoutException.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/media/ProcessRunnerTest.java`

**Interfaces:**
- Produces:
  - `record ProcessResult(int exitCode, String output)`（stdout+stderr 合并输出）
  - `ProcessRunner.run(List<String> command, Duration timeout)`：超时强杀抛 `ProcessTimeoutException`；无法启动抛 `ProcessFailedException`；正常退出即返回（非零退出码由调用方处理）
  - `ProcessFailedException extends RuntimeException`、`ProcessTimeoutException extends RuntimeException`

- [ ] **Step 1: 写测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/media/ProcessRunnerTest.java`：

```java
package com.waveminer.waveminer.media;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void capturesOutputAndExitCode() {
        ProcessResult r = runner.run(List.of("echo", "hello wave"), Duration.ofSeconds(10));
        assertThat(r.exitCode()).isZero();
        assertThat(r.output()).contains("hello wave");
    }

    @Test
    void nonzeroExitCodeReturned() {
        ProcessResult r = runner.run(List.of("sh", "-c", "echo boom >&2; exit 3"), Duration.ofSeconds(10));
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.output()).contains("boom");
    }

    @Test
    void timeoutKillsProcess() {
        assertThatThrownBy(() ->
                runner.run(List.of("sleep", "5"), Duration.ofMillis(300)))
                .isInstanceOf(ProcessTimeoutException.class);
    }

    @Test
    void missingBinaryFails() {
        assertThatThrownBy(() ->
                runner.run(List.of("definitely-not-a-real-binary-xyz"), Duration.ofSeconds(5)))
                .isInstanceOf(ProcessFailedException.class);
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=ProcessRunnerTest`
Expected: FAIL，编译错误

- [ ] **Step 2: 实现**

`media/ProcessResult.java`：

```java
package com.waveminer.waveminer.media;

public record ProcessResult(int exitCode, String output) {}
```

`media/ProcessFailedException.java`：

```java
package com.waveminer.waveminer.media;

public class ProcessFailedException extends RuntimeException {
    public ProcessFailedException(String message) { super(message); }
    public ProcessFailedException(String message, Throwable cause) { super(message, cause); }
}
```

`media/ProcessTimeoutException.java`：

```java
package com.waveminer.waveminer.media;

public class ProcessTimeoutException extends RuntimeException {
    public ProcessTimeoutException(String message) { super(message); }
}
```

`media/ProcessRunner.java`：

```java
package com.waveminer.waveminer.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ProcessRunner {

    public ProcessResult run(List<String> command, Duration timeout) {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ProcessFailedException("无法启动进程: " + command.get(0) + "（请确认已安装并在 PATH 中）", e);
        }
        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            p.destroyForcibly();
            throw new UncheckedIOException(e);
        }
        boolean finished;
        try {
            finished = p.waitFor(Math.max(1, timeout.toSeconds()), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new ProcessFailedException("进程等待被中断: " + command.get(0), e);
        }
        if (!finished) {
            p.destroyForcibly();
            throw new ProcessTimeoutException("进程超时（" + timeout.toSeconds() + "s）: " + command.get(0));
        }
        return new ProcessResult(p.exitValue(), output);
    }
}
```

- [ ] **Step 3: 运行测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend && git commit -m "feat: ProcessRunner 外部进程封装"
```

---

### Task 5: FfmpegService + YtDlpService + 工具健康检查

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/media/FfmpegService.java`、`media/YtDlpService.java`、`media/ToolHealthChecker.java`、`api/ToolsController.java`、`config/RunnerConfig.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/media/FfmpegServiceTest.java`

**Interfaces:**
- Consumes: `ProcessRunner.run(List<String>, Duration)`、`ProcessResult`、`ProcessFailedException`、`AppProperties`
- Produces:
  - `FfmpegService`：`double probeDurationSeconds(Path)`、`void extractAudio(Path in, Path out)`、`List<String> ffprobeCommand(Path)`、`List<String> extractAudioCommand(Path, Path)`、`double parseDurationSeconds(String json)`（后四个包级可见供测试）
  - `YtDlpService`：`void download(String url, Path dir)`、`List<String> downloadCommand(String url, Path dir)`、`static boolean isDirectMediaUrl(String url)`、`void directDownload(String url, Path dir)`
  - `ToolHealthChecker`：`record ToolHealth(String name, boolean ok, String version)`、`List<ToolHealth> checkAll()`
  - `GET /api/tools/health` → `[{name, ok, version}]`
  - 两个 Service 通过 `FfmpegService.Runner`/`YtDlpService.Runner` 函数式接口消费 `ProcessRunner::run`（由 `RunnerConfig` 装配，测试可直接传 lambda）

- [ ] **Step 1: 写测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/media/FfmpegServiceTest.java`：

```java
package com.waveminer.waveminer.media;

import com.waveminer.waveminer.config.AppProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfmpegServiceTest {

    private List<String> lastCommand;

    private final FfmpegService service = new FfmpegService(
            (command, timeout) -> {
                lastCommand = command;
                if (command.get(0).equals("ffprobe")) {
                    return new ProcessResult(0, "{\"format\":{\"duration\":\"12.5\"}}");
                }
                return new ProcessResult(0, "");
            },
            new AppProperties());

    @Test
    void extractCommandShape() {
        List<String> cmd = service.extractAudioCommand(Path.of("/v/in.mp4"), Path.of("/v/out.m4a"));
        assertThat(cmd).startsWith("ffmpeg", "-y", "-i", "/v/in.mp4")
                .contains("-vn", "-ac", "1", "-ar", "16000", "-c:a", "aac")
                .endsWith("/v/out.m4a");
    }

    @Test
    void probeParsesDurationJson() {
        double d = service.probeDurationSeconds(Path.of("/v/a.mp4"));
        assertThat(d).isEqualTo(12.5);
        assertThat(lastCommand.get(0)).isEqualTo("ffprobe");
        assertThat(lastCommand).contains("-print_format", "json", "-show_format");
    }

    @Test
    void probeFailsOnError() {
        FfmpegService failing = new FfmpegService(
                (command, timeout) -> new ProcessResult(1, "Invalid data"), new AppProperties());
        assertThatThrownBy(() -> failing.probeDurationSeconds(Path.of("/v/a.mp4")))
                .isInstanceOf(ProcessFailedException.class)
                .hasMessageContaining("ffprobe");
    }

    @Test
    void parsesBareJson() {
        assertThat(service.parseDurationSeconds("{\"format\":{\"duration\":\"3.75\"}}")).isEqualTo(3.75);
        assertThatThrownBy(() -> service.parseDurationSeconds("{\"format\":{}}"))
                .isInstanceOf(ProcessFailedException.class);
    }

    @Test
    void directMediaUrlDetection() {
        assertThat(YtDlpService.isDirectMediaUrl("https://cdn.example.com/movie.mp4")).isTrue();
        assertThat(YtDlpService.isDirectMediaUrl("https://cdn.example.com/song.MP3")).isTrue();
        assertThat(YtDlpService.isDirectMediaUrl("https://www.bilibili.com/video/BV1xx")).isFalse();
        assertThat(YtDlpService.isDirectMediaUrl("https://example.com/watch?v=1")).isFalse();
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=FfmpegServiceTest`
Expected: FAIL，编译错误

- [ ] **Step 2: 实现 FfmpegService**

`media/FfmpegService.java`：

```java
package com.waveminer.waveminer.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waveminer.waveminer.config.AppProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class FfmpegService {

    public interface Runner {
        ProcessResult run(List<String> command, Duration timeout);
    }

    private final Runner runner;
    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public FfmpegService(Runner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    List<String> ffprobeCommand(Path file) {
        return List.of(props.getFfprobePath(), "-v", "error", "-print_format", "json",
                "-show_format", file.toString());
    }

    List<String> extractAudioCommand(Path in, Path out) {
        return List.of(props.getFfmpegPath(), "-y", "-i", in.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "aac", out.toString());
    }

    public double probeDurationSeconds(Path file) {
        ProcessResult r = runner.run(ffprobeCommand(file), props.processTimeout());
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("ffprobe 探测时长失败:\n" + tail(r.output()));
        }
        return parseDurationSeconds(r.output());
    }

    double parseDurationSeconds(String json) {
        try {
            JsonNode node = mapper.readTree(json).path("format").path("duration");
            if (node.isMissingNode() || node.asText().isBlank()) {
                throw new ProcessFailedException("ffprobe 结果缺少 format.duration");
            }
            return Double.parseDouble(node.asText());
        } catch (ProcessFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ProcessFailedException("ffprobe 输出解析失败: " + e.getMessage(), e);
        }
    }

    public void extractAudio(Path in, Path out) {
        ProcessResult r = runner.run(extractAudioCommand(in, out), props.processTimeout());
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("ffmpeg 提取音频失败:\n" + tail(r.output()));
        }
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }
}
```

- [ ] **Step 3: 实现 YtDlpService**

`media/YtDlpService.java`：

```java
package com.waveminer.waveminer.media;

import com.waveminer.waveminer.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class YtDlpService {

    public interface Runner {
        ProcessResult run(List<String> command, Duration timeout);
    }

    private final Runner runner;
    private final AppProperties props;

    public YtDlpService(Runner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    List<String> downloadCommand(String url, Path dir) {
        return List.of(props.getYtDlpPath(), "--no-playlist",
                "-f", "bv*+ba/b",
                "-o", dir.resolve("source.%(ext)s").toString(),
                "--newline", url);
    }

    public void download(String url, Path dir) {
        ProcessResult r = runner.run(downloadCommand(url, dir), props.processTimeout());
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("yt-dlp 下载失败:\n" + tail(r.output()));
        }
    }

    /** 直链回退：流式下载到 dir/source.<ext> */
    public void directDownload(String url, Path dir) {
        String ext = extensionOfUrl(url);
        if (ext.isBlank() || !MediaTypes.ALL_EXTENSIONS.contains(ext)) {
            throw new ProcessFailedException("直链无法判断受支持的文件扩展名: " + url);
        }
        Path dest = dir.resolve("source." + ext);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(props.processTimeout())
                .GET().build();
        try {
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new ProcessFailedException("直链下载失败，HTTP " + resp.statusCode());
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ProcessFailedException("直链下载 IO 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessFailedException("直链下载被中断", e);
        }
    }

    public static boolean isDirectMediaUrl(String url) {
        try {
            String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
            int dot = path.lastIndexOf('.');
            return dot >= 0 && dot < path.length() - 1
                    && MediaTypes.ALL_EXTENSIONS.contains(path.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String extensionOfUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            int dot = path.lastIndexOf('.');
            return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }
}
```

- [ ] **Step 4: 实现 RunnerConfig + ToolHealthChecker + ToolsController**

`config/RunnerConfig.java`：

```java
package com.waveminer.waveminer.config;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.media.ProcessRunner;
import com.waveminer.waveminer.media.YtDlpService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RunnerConfig {

    @Bean
    public FfmpegService ffmpegService(ProcessRunner pr, AppProperties props) {
        return new FfmpegService(pr::run, props);
    }

    @Bean
    public YtDlpService ytDlpService(ProcessRunner pr, AppProperties props) {
        return new YtDlpService(pr::run, props);
    }
}
```

`media/ToolHealthChecker.java`：

```java
package com.waveminer.waveminer.media;

import com.waveminer.waveminer.config.AppProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ToolHealthChecker {

    public record ToolHealth(String name, boolean ok, String version) {}

    private final ProcessRunner runner;
    private final AppProperties props;

    public ToolHealthChecker(ProcessRunner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    public List<ToolHealth> checkAll() {
        List<ToolHealth> out = new ArrayList<>();
        out.add(check("ffmpeg", props.getFfmpegPath()));
        out.add(check("ffprobe", props.getFfprobePath()));
        out.add(check("yt-dlp", props.getYtDlpPath()));
        return out;
    }

    private ToolHealth check(String name, String bin) {
        try {
            ProcessResult r = runner.run(List.of(bin, "--version"), Duration.ofSeconds(15));
            String firstLine = r.output().lines().findFirst().orElse("").trim();
            return new ToolHealth(name, r.exitCode() == 0, r.exitCode() == 0 ? firstLine : "不可用");
        } catch (RuntimeException e) {
            return new ToolHealth(name, false, "不可用: " + e.getMessage());
        }
    }
}
```

`api/ToolsController.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.media.ToolHealthChecker;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
public class ToolsController {

    private final ToolHealthChecker checker;

    public ToolsController(ToolHealthChecker checker) { this.checker = checker; }

    @GetMapping("/health")
    public List<ToolHealthChecker.ToolHealth> health() {
        return checker.checkAll();
    }
}
```

- [ ] **Step 5: 运行全部测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend && git commit -m "feat: ffmpeg/yt-dlp 服务封装与工具健康检查"
```

---

### Task 6: PipelineService（组合校验 + 任务创建）

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/pipeline/PipelineService.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/pipeline/PipelineServiceTest.java`

**Interfaces:**
- Consumes: `TaskRepository`（Task 2）、`ArtifactStore`（Task 3）、`MediaTypes`（Task 3）；依赖 `PipelineService.Runner` 接口（本任务定义，Task 7 的 `TaskRunner` 实现）
- Produces:
  - `record CreateTaskCommand(String url, org.springframework.web.multipart.MultipartFile file, List<StageType> stages, String language)`
  - `interface Runner { void submit(Task task); }`
  - `PipelineService.create(CreateTaskCommand): Task` —— 校验失败抛 `IllegalArgumentException`（中文信息）；创建任务、保存上传文件为 `source.<ext>`、落库、调用 `runner.submit(task)`
  - 校验规则（规格 §4.2）：stages 非空且无重复；URL 模式必须含 DOWNLOAD、不能带文件、url 必须为 http(s)；UPLOAD 模式必须带文件、不能含 DOWNLOAD；上传文件扩展名必须在 `MediaTypes.ALL_EXTENSIONS` 内；含 AUDIO_EXTRACT 且不含 DOWNLOAD 时文件必须是视频

- [ ] **Step 1: 写测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/pipeline/PipelineServiceTest.java`：

```java
package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.store.ArtifactStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineServiceTest {

    @TempDir
    Path tmp;

    private StubRunner runner;
    private PipelineService service;

    static class StubRunner implements PipelineService.Runner {
        final List<Task> submitted = new ArrayList<>();
        @Override public void submit(Task t) { submitted.add(t); }
    }

    @BeforeEach
    void setUp() {
        var props = new com.waveminer.waveminer.config.AppProperties();
        props.setDataDir(tmp.resolve("data").toString());
        runner = new StubRunner();
        // 注意：不能用 jdbc:sqlite::memory:——DriverManagerDataSource 每次新建连接，
        // 内存库互不可见；用临时文件库保证跨连接可见
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("pipeline-test.db"));
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS task (id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_ref TEXT, title TEXT NOT NULL, stages TEXT NOT NULL, status TEXT NOT NULL, error_message TEXT, created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_stage (task_id TEXT NOT NULL, stage_type TEXT NOT NULL, status TEXT NOT NULL, detail TEXT, artifact_path TEXT, PRIMARY KEY (task_id, stage_type))");
        service = new PipelineService(new TaskRepository(jdbc), new ArtifactStore(props), runner);
    }

    private CreateTaskCommand url(String url, List<StageType> stages) {
        return new CreateTaskCommand(url, null, stages, null);
    }

    private CreateTaskCommand upload(String filename, List<StageType> stages) {
        return new CreateTaskCommand(null,
                new MockMultipartFile("file", filename, "application/octet-stream", new byte[]{1, 2, 3}),
                stages, null);
    }

    @Test
    void urlWithDownloadCreatesTaskAndSubmits() {
        Task t = service.create(url("https://example.com/video/BV1x", List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT)));
        assertThat(t.getId()).isNotBlank();
        assertThat(t.getSourceType()).isEqualTo(SourceType.URL);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(runner.submitted).hasSize(1);
    }

    @Test
    void uploadVideoWithAudioExtract() {
        Task t = service.create(upload("my.mp4", List.of(StageType.AUDIO_EXTRACT)));
        assertThat(t.getSourceType()).isEqualTo(SourceType.UPLOAD);
        assertThat(t.getTitle()).isEqualTo("my.mp4");
    }

    @Test
    void urlRequiresDownloadStage() {
        assertThatThrownBy(() -> service.create(url("https://e.com/a.mp4", List.of(StageType.AUDIO_EXTRACT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("链接");
    }

    @Test
    void uploadMustNotContainDownload() {
        assertThatThrownBy(() -> service.create(upload("a.mp4", List.of(StageType.DOWNLOAD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上传");
    }

    @Test
    void audioExtractWithoutDownloadNeedsVideo() {
        assertThatThrownBy(() -> service.create(upload("song.mp3", List.of(StageType.AUDIO_EXTRACT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频");
    }

    @Test
    void unsupportedExtensionRejected() {
        assertThatThrownBy(() -> service.create(upload("doc.pdf", List.of(StageType.AUDIO_EXTRACT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件类型");
    }

    @Test
    void emptyStagesRejected() {
        assertThatThrownBy(() -> service.create(url("https://e.com/a", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void badUrlRejected() {
        assertThatThrownBy(() -> service.create(url("ftp://e.com/a", List.of(StageType.DOWNLOAD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    @Test
    void urlAndFileTogetherRejected() {
        assertThatThrownBy(() -> service.create(new CreateTaskCommand(
                "https://e.com/a",
                new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[]{1}),
                List.of(StageType.DOWNLOAD), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=PipelineServiceTest`
Expected: FAIL，编译错误

- [ ] **Step 2: 实现 PipelineService**

`pipeline/PipelineService.java`：

```java
package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.media.MediaTypes;
import com.waveminer.waveminer.store.ArtifactStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PipelineService {

    public record CreateTaskCommand(String url, MultipartFile file, List<StageType> stages, String language) {}

    public interface Runner {
        void submit(Task task);
    }

    private final TaskRepository repo;
    private final ArtifactStore artifacts;
    private final Runner runner;

    public PipelineService(TaskRepository repo, ArtifactStore artifacts, Runner runner) {
        this.repo = repo;
        this.artifacts = artifacts;
        this.runner = runner;
    }

    public Task create(CreateTaskCommand cmd) {
        validate(cmd);
        boolean isUrl = cmd.url() != null && !cmd.url().isBlank();

        Task t = new Task();
        t.setId(UUID.randomUUID().toString());
        t.setSourceType(isUrl ? SourceType.URL : SourceType.UPLOAD);
        t.setSourceRef(isUrl ? cmd.url().trim() : cmd.file().getOriginalFilename());
        t.setTitle(isUrl ? titleFromUrl(cmd.url()) : cmd.file().getOriginalFilename());
        t.setStages(List.copyOf(new LinkedHashSet<>(cmd.stages())));
        t.setStatus(TaskStatus.PENDING);
        t.setCreatedAt(Instant.now());

        if (!isUrl) {
            saveUpload(t.getId(), cmd.file());
        }

        repo.insert(t);
        repo.insertStages(t.getId(), t.getStages());
        runner.submit(t);
        return t;
    }

    private void validate(CreateTaskCommand cmd) {
        List<StageType> stages = cmd.stages();
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个功能（下载/提取音频/转写）");
        }
        Set<StageType> distinct = new LinkedHashSet<>(stages);
        if (distinct.size() != stages.size()) {
            throw new IllegalArgumentException("功能选择存在重复");
        }
        boolean hasUrl = cmd.url() != null && !cmd.url().isBlank();
        boolean hasFile = cmd.file() != null && !cmd.file().isEmpty();
        boolean hasDownload = stages.contains(StageType.DOWNLOAD);

        if (hasUrl && hasFile) {
            throw new IllegalArgumentException("视频链接和文件上传只能选择一种输入方式");
        }
        if (!hasUrl && !hasFile) {
            throw new IllegalArgumentException("请提供视频链接或上传文件");
        }
        if (hasUrl) {
            if (!hasDownload) {
                throw new IllegalArgumentException("使用链接输入时必须勾选\"下载\"功能");
            }
            String u = cmd.url().trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                throw new IllegalArgumentException("仅支持 http(s) 链接");
            }
            try {
                URI.create(u);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("链接格式无效");
            }
        }
        if (hasFile) {
            if (hasDownload) {
                throw new IllegalArgumentException("上传文件时不能勾选\"下载\"功能");
            }
            String filename = cmd.file().getOriginalFilename();
            String ext = MediaTypes.extension(filename);
            if (!MediaTypes.ALL_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException("不支持的文件类型：" + (ext.isBlank() ? "（无扩展名）" : ext));
            }
            if (stages.contains(StageType.AUDIO_EXTRACT) && !MediaTypes.isVideo(filename)) {
                throw new IllegalArgumentException("提取音频需要视频文件（当前为 ." + ext + "）");
            }
        }
    }

    private void saveUpload(String taskId, MultipartFile file) {
        Path dir = artifacts.taskDir(taskId);
        String ext = MediaTypes.extension(file.getOriginalFilename());
        Path dest = dir.resolve("source." + ext);
        try (var in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("保存上传文件失败", e);
        }
    }

    private static String titleFromUrl(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            int slash = path.lastIndexOf('/');
            String last = slash >= 0 ? path.substring(slash + 1) : path;
            return last.isBlank() ? url.trim() : last;
        } catch (IllegalArgumentException e) {
            return url.trim();
        }
    }
}
```

- [ ] **Step 3: 运行全部测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend && git commit -m "feat: 任务组合校验与创建 PipelineService"
```

---

### Task 7: TaskRunner（虚拟线程执行器）+ 启动恢复

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/pipeline/StageHandler.java`、`pipeline/TaskContext.java`、`pipeline/TaskRunner.java`、`pipeline/StartupRecovery.java`
- Modify: `backend/pom.xml`（awaitility test 依赖）
- Test: `backend/src/test/java/com/waveminer/waveminer/pipeline/TaskRunnerTest.java`

**Interfaces:**
- Consumes: `TaskRepository`、`ArtifactStore`、`PipelineService.Runner`
- Produces:
  - `interface StageHandler { StageType type(); void run(TaskContext ctx); }`
  - `final class TaskContext`：`String taskId()`、`Path dir()`、`String sourceUrl()`、`Optional<Path> uploadedFile()`、`Path artifactOf(StageType)`（无则 null）、`void putArtifact(StageType, String fileName)`、`boolean cancelRequested()`、`static Path findUploadedFile(Path dir)`、包级 `String artifactNameOf(StageType)`
  - `TaskRunner implements PipelineService.Runner`：`submit(Task)`、`boolean cancel(String taskId)`；`Semaphore(maxConcurrentTasks)` 限并发；任务执行：PENDING→RUNNING→逐 stage→COMPLETED/FAILED/CANCELLED；stage 异常 → 该 stage FAILED（detail=异常消息）+ 任务 FAILED；已 DONE 产物保留；重启恢复由 `StartupRecovery`（ApplicationRunner）调用 `repo.markStaleRunningAsFailed("应用重启导致中断，可重试")`

- [ ] **Step 1: pom.xml 增加 awaitility（test scope）**

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

（版本由 Spring Boot BOM 管理）

- [ ] **Step 2: 写测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/pipeline/TaskRunnerTest.java`：

```java
package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.store.ArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunnerTest {

    @TempDir
    Path tmp;

    private TaskRepository repo;
    private TaskRunner runner;
    private final Map<StageType, RuntimeException> failures = new ConcurrentHashMap<>();

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
                if (failures.containsKey(StageType.DOWNLOAD)) throw failures.get(StageType.DOWNLOAD);
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
                if (failures.containsKey(StageType.AUDIO_EXTRACT)) throw failures.get(StageType.AUDIO_EXTRACT);
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
```

Run: `cd backend && ./mvnw -q test -Dtest=TaskRunnerTest`
Expected: FAIL，编译错误

- [ ] **Step 3: 实现**

`pipeline/StageHandler.java`：

```java
package com.waveminer.waveminer.pipeline;

public interface StageHandler {
    StageType type();

    void run(TaskContext ctx);
}
```

`pipeline/TaskContext.java`：

```java
package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.store.ArtifactStore;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TaskContext {
    private final String taskId;
    private final Path dir;
    private final String sourceUrl;
    private final Path uploadedFile;
    private final AtomicBoolean cancel;
    private final Map<StageType, String> artifacts = new EnumMap<>(StageType.class);

    public TaskContext(String taskId, Path dir, String sourceUrl, Path uploadedFile, AtomicBoolean cancel) {
        this.taskId = taskId;
        this.dir = dir;
        this.sourceUrl = sourceUrl;
        this.uploadedFile = uploadedFile;
        this.cancel = cancel;
    }
    public String taskId() { return taskId; }

    public Path dir() { return dir; }

    public String sourceUrl() { return sourceUrl; }

    public Optional<Path> uploadedFile() { return Optional.ofNullable(uploadedFile); }

    public boolean cancelRequested() { return cancel.get(); }

    public Path artifactOf(StageType type) {
        String name = artifacts.get(type);
        return name == null ? null : dir.resolve(name);
    }

    public void putArtifact(StageType type, String fileName) {
        artifacts.put(type, fileName);
    }

    String artifactNameOf(StageType type) { return artifacts.get(type); }

    static Path findUploadedFile(Path dir) {
        return ArtifactStore.findSourceFile(dir).orElse(null);
    }
}
```

`pipeline/TaskRunner.java`：

```java
package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.store.ArtifactStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();

    public TaskRunner(AppProperties props, List<StageHandler> handlers,
                      TaskRepository repo, ArtifactStore store) {
        this.permits = new Semaphore(Math.max(1, props.getMaxConcurrentTasks()));
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(StageHandler::type, Function.identity()));
        this.repo = repo;
        this.store = store;
    }

    @Override
    public void submit(Task task) {
        repo.updateTaskStatus(task.getId(), TaskStatus.PENDING, null);
        cancels.put(task.getId(), new AtomicBoolean(false));
        Thread.ofVirtual().name("wave-miner-task-" + task.getId()).start(() -> runTask(task.getId()));
    }

    public boolean cancel(String taskId) {
        AtomicBoolean flag = cancels.get(taskId);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    private void runTask(String taskId) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancels.remove(taskId);
            return;
        }
        try {
            execute(taskId);
        } catch (Exception e) {
            log.error("任务执行异常 task={}", taskId, e);
            repo.updateTaskStatus(taskId, TaskStatus.FAILED, "内部错误: " + e.getMessage());
            repo.updateFinishedAt(taskId, Instant.now());
        } finally {
            cancels.remove(taskId);
            permits.release();
        }
    }

    private void execute(String taskId) {
        Task task = repo.find(taskId).orElse(null);
        AtomicBoolean cancel = cancels.get(taskId);
        if (task == null || cancel == null) return;

        if (cancel.get()) {
            repo.updateTaskStatus(taskId, TaskStatus.CANCELLED, null);
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
                cancel);

        for (StageType type : task.getStages()) {
            if (cancel.get()) {
                markRemainingSkipped(task, type);
                repo.updateTaskStatus(taskId, TaskStatus.CANCELLED, null);
                repo.updateFinishedAt(taskId, Instant.now());
                return;
            }
            StageHandler handler = handlers.get(type);
            if (handler == null) {
                fail(taskId, type, "没有可用的处理器: " + type);
                return;
            }
            repo.updateStage(taskId, type, StageStatus.RUNNING, null, null);
            try {
                handler.run(ctx);
            } catch (Exception e) {
                fail(taskId, type, e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }
            repo.updateStage(taskId, type, StageStatus.DONE, null, ctx.artifactNameOf(type));
        }

        repo.updateTaskStatus(taskId, TaskStatus.COMPLETED, null);
        repo.updateFinishedAt(taskId, Instant.now());
    }

    private void fail(String taskId, StageType type, String message) {
        repo.updateStage(taskId, type, StageStatus.FAILED, message, null);
        repo.updateTaskStatus(taskId, TaskStatus.FAILED, message);
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
```

`pipeline/StartupRecovery.java`：

```java
package com.waveminer.waveminer.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final TaskRepository repo;

    public StartupRecovery(TaskRepository repo) { this.repo = repo; }

    @Override
    public void run(ApplicationArguments args) {
        repo.markStaleRunningAsFailed("应用重启导致中断，可重试");
        log.info("启动恢复检查完成");
    }
}
```

- [ ] **Step 4: 运行全部测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: 虚拟线程任务执行器与启动恢复"
```

---

### Task 8: DownloadStage + AudioExtractStage（真实实现）

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/stage/DownloadStage.java`、`stage/AudioExtractStage.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/stage/DownloadStageTest.java`、`stage/AudioExtractStageTest.java`

**Interfaces:**
- Consumes: `YtDlpService.download/directDownload/isDirectMediaUrl`（Task 5）、`FfmpegService.extractAudio`（Task 5）、`ArtifactStore.findSourceFile`（Task 3）、`StageHandler`/`TaskContext`（Task 7）
- Produces（`@Component`，被 `TaskRunner` 按 `StageType` 自动发现）:
  - `DownloadStage`：要求 `ctx.sourceUrl() != null`（否则 `IllegalStateException("下载功能需要视频链接")`）；先 `ytDlp.download`，失败且 `isDirectMediaUrl(url)` 时回退 `directDownload`；成功后 `ArtifactStore.findSourceFile` → `putArtifact(DOWNLOAD, 文件名)`，找不到产物抛 `IllegalStateException("下载完成但未找到产物文件")`
  - `AudioExtractStage`：输入 = `ctx.artifactOf(DOWNLOAD)` 优先，否则 `ctx.uploadedFile()`；皆无抛 `IllegalStateException("提取音频缺少视频输入（需要下载产物或上传视频文件）")`；`ffmpeg.extractAudio(input, dir/audio.m4a)` → `putArtifact(AUDIO_EXTRACT, "audio.m4a")`

- [ ] **Step 1: 写测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/stage/DownloadStageTest.java`：

```java
package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.media.ProcessFailedException;
import com.waveminer.waveminer.media.YtDlpService;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DownloadStageTest {

    @TempDir
    Path tmp;

    private TaskContext ctx(String id, String url) throws Exception {
        return new TaskContext(id, Files.createDirectories(tmp.resolve(id)), url, null, new AtomicBoolean(false));
    }

    @Test
    void downloadsViaYtDlpAndRecordsArtifact() throws Exception {
        TaskContext c = ctx("t1", "https://e.com/a");
        YtDlpService yt = mock(YtDlpService.class);
        doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.writeString(dir.resolve("source.mp4"), "v");
            return null;
        }).when(yt).download(eq("https://e.com/a"), any());

        new DownloadStage(yt).run(c);

        verify(yt).download(eq("https://e.com/a"), any());
        assertThat(c.artifactOf(StageType.DOWNLOAD)).hasFileName("source.mp4");
    }

    @Test
    void fallsBackToDirectDownload() throws Exception {
        TaskContext c = ctx("t2", "https://cdn.e.com/a.mp4");
        YtDlpService yt = mock(YtDlpService.class);
        doThrow(new ProcessFailedException("yt-dlp 失败")).when(yt).download(any(), any());
        doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.writeString(dir.resolve("source.mp4"), "v");
            return null;
        }).when(yt).directDownload("https://cdn.e.com/a.mp4", c.dir());

        new DownloadStage(yt).run(c);

        verify(yt).directDownload("https://cdn.e.com/a.mp4", c.dir());
        assertThat(c.artifactOf(StageType.DOWNLOAD)).hasFileName("source.mp4");
    }

    @Test
    void directFallbackFailureStillFails() throws Exception {
        TaskContext c = ctx("t3", "https://cdn.e.com/a.mp4");
        YtDlpService yt = mock(YtDlpService.class);
        doThrow(new ProcessFailedException("yt-dlp 失败")).when(yt).download(any(), any());
        doThrow(new ProcessFailedException("直链也失败")).when(yt).directDownload(any(), any());

        assertThatThrownBy(() -> new DownloadStage(yt).run(c))
                .isInstanceOf(ProcessFailedException.class)
                .hasMessageContaining("直链也失败");
    }

    @Test
    void nonDirectUrlYtDlpFailurePropagates() throws Exception {
        TaskContext c = ctx("t4", "https://www.bilibili.com/video/BV1x");
        YtDlpService yt = mock(YtDlpService.class);
        doThrow(new ProcessFailedException("平台下载失败")).when(yt).download(any(), any());

        assertThatThrownBy(() -> new DownloadStage(yt).run(c))
                .isInstanceOf(ProcessFailedException.class)
                .hasMessageContaining("平台下载失败");
    }

    @Test
    void requiresSourceUrl() {
        YtDlpService yt = mock(YtDlpService.class);
        TaskContext c = new TaskContext("t5", tmp, null, null, new AtomicBoolean(false));
        assertThatThrownBy(() -> new DownloadStage(yt).run(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("链接");
    }

    @Test
    void noSourceFileAfterDownloadFails() throws Exception {
        TaskContext c = ctx("t6", "https://e.com/a");
        YtDlpService yt = mock(YtDlpService.class);
        doNothing().when(yt).download(any(), any());

        assertThatThrownBy(() -> new DownloadStage(yt).run(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("产物");
    }
}
```

`backend/src/test/java/com/waveminer/waveminer/stage/AudioExtractStageTest.java`：

```java
package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AudioExtractStageTest {

    @TempDir
    Path tmp;

    private TaskContext ctx(String id, String url, Path uploaded) throws Exception {
        return new TaskContext(id, Files.createDirectories(tmp.resolve(id)), url, uploaded, new AtomicBoolean(false));
    }

    @Test
    void extractsFromDownloadArtifact() throws Exception {
        TaskContext c = ctx("t1", null, null);
        Path video = c.dir().resolve("source.mp4");
        Files.writeString(video, "v");
        c.putArtifact(StageType.DOWNLOAD, "source.mp4");
        FfmpegService ffmpeg = mock(FfmpegService.class);
        doAnswer(inv -> {
            Files.writeString(c.dir().resolve("audio.m4a"), "a");
            return null;
        }).when(ffmpeg).extractAudio(eq(video), eq(c.dir().resolve("audio.m4a")));

        new AudioExtractStage(ffmpeg).run(c);

        assertThat(c.artifactOf(StageType.AUDIO_EXTRACT)).hasFileName("audio.m4a");
    }

    @Test
    void extractsFromUploadedFile() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("t2"));
        Path uploaded = dir.resolve("source.mkv");
        Files.writeString(uploaded, "v");
        TaskContext c = new TaskContext("t2", dir, null, uploaded, new AtomicBoolean(false));
        FfmpegService ffmpeg = mock(FfmpegService.class);
        doAnswer(inv -> {
            Files.writeString(dir.resolve("audio.m4a"), "a");
            return null;
        }).when(ffmpeg).extractAudio(any(), any());

        new AudioExtractStage(ffmpeg).run(c);

        assertThat(c.artifactOf(StageType.AUDIO_EXTRACT)).hasFileName("audio.m4a");
    }

    @Test
    void missingInputFails() throws Exception {
        TaskContext c = ctx("t3", "https://e.com/a", null);
        assertThatThrownBy(() -> new AudioExtractStage(mock(FfmpegService.class)).run(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("输入");
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest='DownloadStageTest,AudioExtractStageTest'`
Expected: FAIL，编译错误（Mockito 已随 spring-boot-starter-test 提供）

- [ ] **Step 2: 实现**

`stage/DownloadStage.java`：

```java
package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.media.ProcessFailedException;
import com.waveminer.waveminer.media.YtDlpService;
import com.waveminer.waveminer.pipeline.StageHandler;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskContext;
import com.waveminer.waveminer.store.ArtifactStore;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class DownloadStage implements StageHandler {

    private final YtDlpService ytDlp;

    public DownloadStage(YtDlpService ytDlp) { this.ytDlp = ytDlp; }

    @Override
    public StageType type() { return StageType.DOWNLOAD; }

    @Override
    public void run(TaskContext ctx) {
        String url = ctx.sourceUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("下载功能需要视频链接");
        }
        try {
            ytDlp.download(url, ctx.dir());
        } catch (ProcessFailedException e) {
            if (!YtDlpService.isDirectMediaUrl(url)) {
                throw e;
            }
            ytDlp.directDownload(url, ctx.dir());
        }
        Path source = ArtifactStore.findSourceFile(ctx.dir())
                .orElseThrow(() -> new IllegalStateException("下载完成但未找到产物文件"));
        ctx.putArtifact(StageType.DOWNLOAD, source.getFileName().toString());
    }
}
```

`stage/AudioExtractStage.java`：

```java
package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.pipeline.StageHandler;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskContext;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class AudioExtractStage implements StageHandler {

    private final FfmpegService ffmpeg;

    public AudioExtractStage(FfmpegService ffmpeg) { this.ffmpeg = ffmpeg; }

    @Override
    public StageType type() { return StageType.AUDIO_EXTRACT; }

    @Override
    public void run(TaskContext ctx) {
        Path input = ctx.artifactOf(StageType.DOWNLOAD);
        if (input == null) {
            input = ctx.uploadedFile().orElse(null);
        }
        if (input == null) {
            throw new IllegalStateException("提取音频缺少视频输入（需要下载产物或上传视频文件）");
        }
        Path out = ctx.dir().resolve("audio.m4a");
        ffmpeg.extractAudio(input, out);
        ctx.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
    }
}
```

- [ ] **Step 3: 运行全部测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend && git commit -m "feat: 下载与音频提取 Stage 实现"
```

---

### Task 9: REST API（任务 CRUD + 产物下载含 Range）+ 访问令牌 + 异常处理

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/api/TaskController.java`、`api/TaskViews.java`、`api/ApiExceptionHandler.java`、`api/AccessTokenInterceptor.java`、`config/WebConfig.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/api/TaskApiTest.java`

**Interfaces:**
- Consumes: `PipelineService`、`TaskRepository`、`ArtifactStore`（`isAllowedArtifactName/taskDir/listArtifacts`）、`TaskRunner.cancel`、`MediaTypes`、`AppProperties.accessToken`
- Produces（前端依赖的 HTTP 契约，前后端字段名严格一致）:
  - `POST /api/tasks`（multipart：`url?`、`file?`、`stages`(CSV)、`language?`）→ 200 `TaskView`
  - `GET /api/tasks?status=&page=0&size=20` → `{"items":[TaskView],"total":n,"page":p,"size":s}`
  - `GET /api/tasks/{id}` → `TaskView`；不存在 → 404
  - `POST /api/tasks/{id}/retry` → 仅 FAILED/CANCELLED，否则 409
  - `POST /api/tasks/{id}/cancel` → 置取消标记（RUNNING/PENDING 有效）
  - `DELETE /api/tasks/{id}` → 删记录+删目录 → `{"ok":true}`
  - `GET /api/tasks/{id}/artifacts/{name}` → 文件流；支持单区间 `Range: bytes=a-b`（206 + `Content-Range`）；非法 name → 400；不存在 → 404；响应含 `Accept-Ranges: bytes`
  - `TaskView` record 字段：`id,title,sourceType,sourceRef,status,errorMessage,createdAt,startedAt,finishedAt,stages,artifacts`；`StageView`：`type,status,detail,artifactName`；`ArtifactView`：`name,sizeBytes`
  - 错误 JSON `{"code","message"}`：`IllegalArgumentException`→400、`NoSuchElementException`/`ArtifactNotFoundException`→404、`IllegalStateException`→409、其余→500
  - `X-Access-Token` 拦截 `/api/**`：`app.access-token` 非空时校验，header 优先、其次查询参数 `token`（媒体标签无法带自定义 header）；不匹配 → 401 JSON

- [ ] **Step 1: 写 MockMvc 测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/api/TaskApiTest.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.media.YtDlpService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
        r.add("app.max-concurrent-tasks", () -> "1");
    }

    @Autowired MockMvc mvc;

    @MockitoBean FfmpegService ffmpeg;
    @MockitoBean YtDlpService ytDlp;

    private static MockMultipartFile mp4() {
        return new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{1, 2, 3, 4});
    }

    private String waitCompleted(String id) throws Exception {
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                mvc.perform(get("/api/tasks/" + id))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
        return id;
    }

    private String createTask(MvcResult created) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void createUploadTaskRunsToCompletion() throws Exception {
        Mockito.doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.writeString(out, "audio");
            return null;
        }).when(ffmpeg).extractAudio(Mockito.any(), Mockito.any());

        MvcResult created = mvc.perform(multipart("/api/tasks")
                        .file(mp4())
                        .param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sourceType").value("UPLOAD"))
                .andReturn();
        String id = createTask(created);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                mvc.perform(get("/api/tasks/" + id))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPLETED"))
                        .andExpect(jsonPath("$.stages[0].status").value("DONE"))
                        .andExpect(jsonPath("$.stages[0].artifactName").value("audio.m4a"))
                        .andExpect(jsonPath("$.artifacts.length()").value(2)));
    }

    @Test
    void createUrlTaskWithMockedYtDlp() throws Exception {
        Mockito.doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.writeString(dir.resolve("source.mp4"), "v");
            return null;
        }).when(ytDlp).download(Mockito.anyString(), Mockito.any());

        MvcResult created = mvc.perform(multipart("/api/tasks")
                        .param("url", "https://example.com/watch/1")
                        .param("stages", "DOWNLOAD"))
                .andExpect(status().isOk())
                .andReturn();
        waitCompleted(createTask(created));
    }

    @Test
    void validationErrorReturns400WithChineseMessage() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1}))
                        .param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("文件类型")));
    }

    @Test
    void detailNotFound() throws Exception {
        mvc.perform(get("/api/tasks/nope")).andExpect(status().isNotFound());
    }

    @Test
    void artifactDownloadSupportsRange() throws Exception {
        Mockito.doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.write(dir.resolve("source.mp4"), new byte[1000]);
            return null;
        }).when(ytDlp).download(Mockito.anyString(), Mockito.any());

        MvcResult created = mvc.perform(multipart("/api/tasks")
                        .param("url", "https://example.com/x.mp4")
                        .param("stages", "DOWNLOAD"))
                .andExpect(status().isOk()).andReturn();
        String id = waitCompleted(createTask(created));

        mvc.perform(get("/api/tasks/" + id + "/artifacts/source.mp4"))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"));

        mvc.perform(get("/api/tasks/" + id + "/artifacts/source.mp4").header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-99/1000"));
    }

    @Test
    void artifactTraversalRejected() throws Exception {
        mvc.perform(get("/api/tasks/whatever/artifacts/..%2F..%2Fsecret.txt"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listAndDelete() throws Exception {
        mvc.perform(multipart("/api/tasks").file(mp4()).param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isOk());
        String body = mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.items[0].id");
        assertThat(id).isNotBlank();
        mvc.perform(delete("/api/tasks/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void retryOnlyForFailedOrCancelled() throws Exception {
        mvc.perform(multipart("/api/tasks").file(mp4()).param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isOk());
        String body = mvc.perform(get("/api/tasks")).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.items[0].id");

        // 任务可能已在跑或已完成；两者都不允许 retry → 409
        mvc.perform(post("/api/tasks/" + id + "/retry"))
                .andExpect(status().isConflict());
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=TaskApiTest`
Expected: FAIL，编译错误（`com.jayway.jsonpath` 随 spring-boot-starter-test 提供）

- [ ] **Step 2: 实现 TaskViews / TaskController / 异常处理 / 拦截器 / WebConfig**

`api/TaskViews.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.pipeline.StageStatus;
import com.waveminer.waveminer.pipeline.StageType;
import java.util.List;

public final class TaskViews {

    public record StageView(StageType type, StageStatus status, String detail, String artifactName) {}

    public record ArtifactView(String name, long sizeBytes) {}

    public record TaskView(String id, String title, String sourceType, String sourceRef,
                           String status, String errorMessage,
                           String createdAt, String startedAt, String finishedAt,
                           List<StageView> stages, List<ArtifactView> artifacts) {}

    public record TaskPage(List<TaskView> items, long total, int page, int size) {}

    private TaskViews() {}
}
```

`api/TaskController.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.media.MediaTypes;
import com.waveminer.waveminer.pipeline.PipelineService;
import com.waveminer.waveminer.pipeline.Task;
import com.waveminer.waveminer.pipeline.TaskRepository;
import com.waveminer.waveminer.pipeline.TaskRunner;
import com.waveminer.waveminer.pipeline.TaskStatus;
import com.waveminer.waveminer.store.ArtifactNotFoundException;
import com.waveminer.waveminer.store.ArtifactStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private static final Map<String, MediaType> MEDIA_TYPES = Map.ofEntries(
            Map.entry("mp4", MediaType.parseMediaType("video/mp4")),
            Map.entry("m4v", MediaType.parseMediaType("video/x-m4v")),
            Map.entry("mkv", MediaType.parseMediaType("video/x-matroska")),
            Map.entry("webm", MediaType.parseMediaType("video/webm")),
            Map.entry("mov", MediaType.parseMediaType("video/quicktime")),
            Map.entry("flv", MediaType.parseMediaType("video/x-flv")),
            Map.entry("ts", MediaType.parseMediaType("video/mp2t")),
            Map.entry("avi", MediaType.parseMediaType("video/x-msvideo")),
            Map.entry("mp3", MediaType.parseMediaType("audio/mpeg")),
            Map.entry("m4a", MediaType.parseMediaType("audio/mp4")),
            Map.entry("wav", MediaType.parseMediaType("audio/wav")),
            Map.entry("aac", MediaType.parseMediaType("audio/aac")),
            Map.entry("flac", MediaType.parseMediaType("audio/flac")),
            Map.entry("opus", MediaType.parseMediaType("audio/opus")),
            Map.entry("ogg", MediaType.parseMediaType("audio/ogg")),
            Map.entry("aiff", MediaType.parseMediaType("audio/aiff")));

    private final PipelineService pipeline;
    private final TaskRepository repo;
    private final ArtifactStore artifacts;
    private final TaskRunner runner;

    public TaskController(PipelineService pipeline, TaskRepository repo,
                          ArtifactStore artifacts, TaskRunner runner) {
        this.pipeline = pipeline;
        this.repo = repo;
        this.artifacts = artifacts;
        this.runner = runner;
    }

    @PostMapping
    public TaskViews.TaskView create(
            @RequestParam(required = false) String url,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam String stages,
            @RequestParam(required = false) String language) {
        List<StageType> stageList = Arrays.stream(stages.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(StageType::valueOf).toList();
        Task t = pipeline.create(new PipelineService.CreateTaskCommand(url, file, stageList, language));
        return toView(t);
    }

    @GetMapping
    public TaskViews.TaskPage list(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        List<TaskViews.TaskView> items = repo.list(status, p, s).stream().map(this::toView).toList();
        return new TaskViews.TaskPage(items, repo.count(status), p, s);
    }

    @GetMapping("/{id}")
    public TaskViews.TaskView detail(@PathVariable String id) {
        return toView(find(id));
    }

    @PostMapping("/{id}/retry")
    public TaskViews.TaskView retry(@PathVariable String id) {
        Task t = find(id);
        if (t.getStatus() != TaskStatus.FAILED && t.getStatus() != TaskStatus.CANCELLED) {
            throw new IllegalStateException("仅失败或已取消的任务可以重试");
        }
        repo.resetTaskForRetry(id);
        repo.resetStagesForRetry(id);
        runner.submit(repo.find(id).orElseThrow());
        return toView(repo.find(id).orElseThrow());
    }

    @PostMapping("/{id}/cancel")
    public TaskViews.TaskView cancel(@PathVariable String id) {
        Task t = find(id);
        runner.cancel(id);
        return toView(t);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) throws IOException {
        find(id);
        runner.cancel(id);
        repo.delete(id);
        deleteDirectoryRecursively(artifacts.taskDir(id));
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/artifacts/{name}")
    public ResponseEntity<Object> artifact(@PathVariable String id,
                                           @PathVariable String name,
                                           jakarta.servlet.http.HttpServletRequest request) throws IOException {
        if (!artifacts.isAllowedArtifactName(name)) {
            throw new IllegalArgumentException("非法的产物名称");
        }
        Path dir = artifacts.taskDir(id);
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new ArtifactNotFoundException("产物不存在: " + name);
        }
        long size = Files.size(file);
        MediaType type = MEDIA_TYPES.getOrDefault(
                MediaTypes.extension(name), MediaType.APPLICATION_OCTET_STREAM);

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null) {
            var m = RANGE_PATTERN.matcher(rangeHeader);
            if (m.matches()) {
                long start = m.group(1).isBlank() ? 0 : Long.parseLong(m.group(1));
                long end = m.group(2).isBlank() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
                if (start > end || start >= size) {
                    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                            .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size).build();
                }
                long len = end - start + 1;
                var resource = new FileSystemResource(file);
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .contentType(type)
                        .contentLength(len)
                        .body(new ByteArrayRangeResource(resource, start, len));
            }
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(type)
                .contentLength(size)
                .body(new FileSystemResource(file));
    }

    /** 简单区间资源：从 start 起读 len 字节 */
    static final class ByteArrayRangeResource extends org.springframework.core.io.Resource {
        private final FileSystemResource delegate;
        private final long start;
        private final long length;

        ByteArrayRangeResource(FileSystemResource delegate, long start, long length) {
            this.delegate = delegate;
            this.start = start;
            this.length = length;
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
            var ch = Files.newByteChannel(delegate.getFile().toPath());
            ch.position(start);
            return new java.io.InputStream() {
                private long remaining = length;
                private final ByteBuffer bb = ByteBuffer.allocate(64 * 1024);

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) == -1 ? -1 : (one[0] & 0xff);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    if (!bb.hasRemaining()) {
                        bb.clear();
                        int n = ch.read(bb);
                        if (n == -1) return -1;
                        bb.flip();
                    }
                    int n = Math.min(len, bb.remaining());
                    bb.get(b, off, n);
                    remaining -= n;
                    return n;
                }

                @Override
                public void close() throws IOException {
                    ch.close();
                }
            };
        }

        @Override public boolean exists() { return delegate.exists(); }
        @Override public String getDescription() { return delegate.getDescription() + " (range)"; }
        @Override public java.io.File getFile() { return delegate.getFile(); }
        @Override public long contentLength() { return length; }
        @Override public long lastModified() throws IOException { return delegate.lastModified(); }
        @Override public org.springframework.core.io.Resource createRelative(String rp) { return delegate.createRelative(rp); }
        @Override public String getFilename() { return delegate.getFilename(); }
        @Override public java.net.URI getURI() throws IOException { return delegate.getURI(); }
        @Override public java.net.URL getURL() throws IOException { return delegate.getURL(); }
        @Override public boolean isReadable() { return delegate.isReadable(); }
        @Override public boolean isOpen() { return false; }
    }

    private Task find(String id) {
        return repo.find(id).orElseThrow(() -> new NoSuchElementException("任务不存在"));
    }

    private TaskViews.TaskView toView(Task t) {
        List<TaskViews.StageView> stages = repo.stagesOf(t.getId()).stream()
                .map(s -> new TaskViews.StageView(s.getStageType(), s.getStatus(), s.getDetail(), s.getArtifactPath()))
                .toList();
        List<TaskViews.ArtifactView> arts = artifacts.listArtifacts(t.getId()).stream()
                .map(a -> new TaskViews.ArtifactView(a.name(), a.sizeBytes()))
                .toList();
        return new TaskViews.TaskView(t.getId(), t.getTitle(), t.getSourceType().name(), t.getSourceRef(),
                t.getStatus().name(), t.getErrorMessage(),
                iso(t.getCreatedAt()), iso(t.getStartedAt()), iso(t.getFinishedAt()), stages, arts);
    }

    private static String iso(java.time.Instant i) { return i == null ? null : i.toString(); }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 删除失败不阻塞任务删除
                }
            });
        }
    }
}
```

注意：文件顶部需 `import java.util.regex.Pattern;`；`ReadableByteChannel` 与 `ArrayList` 若未使用则删除对应 import（以编译器提示为准，保留实际用到的）。

`api/ApiExceptionHandler.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.store.ArtifactNotFoundException;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ArtifactNotFoundException.class)
    public ResponseEntity<Map<String, String>> artifactNotFound(ArtifactNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, String>> multipart(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "上传内容无效: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception e) {
        log.error("API 内部错误", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "服务器内部错误: " + e.getMessage());
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message",
                message == null ? "" : message));
    }
}
```

`api/AccessTokenInterceptor.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.web.servlet.HandlerInterceptor;

public class AccessTokenInterceptor implements HandlerInterceptor {

    private final AppProperties props;

    public AccessTokenInterceptor(AppProperties props) { this.props = props; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String token = props.getAccessToken();
        if (token == null || token.isBlank()) return true;
        String given = request.getHeader("X-Access-Token");
        if (given == null || given.isBlank()) {
            given = request.getParameter("token"); // 媒体标签无法带自定义 header
        }
        if (token.equals(given)) return true;
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"缺少或错误的访问令牌\"}");
        return false;
    }
}
```

`config/WebConfig.java`：

```java
package com.waveminer.waveminer.config;

import com.waveminer.waveminer.api.AccessTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public WebConfig(AppProperties props) { this.props = props; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessTokenInterceptor(props)).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "DELETE");
    }
}
```

- [ ] **Step 3: 运行全部测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend && git commit -m "feat: 任务 REST API、产物 Range 下载与访问令牌"
```

---

### Task 10: 前端（Vue 3 + Element Plus：新建任务/列表/详情）

**Files:**
- Create: `frontend/`（Vite 脚手架）、`frontend/src/api/client.ts`、`src/api/types.ts`、`src/utils/format.ts`、`src/views/NewTaskView.vue`、`src/views/TaskListView.vue`、`src/views/TaskDetailView.vue`、`src/router.ts`、改写 `src/App.vue`、`src/main.ts`、`vite.config.ts`
- Test: `frontend/src/utils/format.test.ts`

**Interfaces:**
- Consumes: Task 9 的 HTTP 契约（字段名与后端 record 一致）
- Produces: 可用 UI；`npm run build` 产物 `frontend/dist/`（Task 11 接入静态托管）

- [ ] **Step 1: 脚手架**

```bash
npm create -y vite@latest frontend -- --template vue-ts
cd frontend && npm install && npm install element-plus @element-plus/icons-vue vue-router@4
npm install -D vitest
```

`frontend/vite.config.ts` 全量替换：

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
})
```

`frontend/index.html` 的 `<title>` 改为 `wave-miner`；删除 `src/components/HelloWorld.vue`、`src/style.css` 及其对 main.ts 的引用。

- [ ] **Step 2: types + api client**

`frontend/src/api/types.ts`：

```ts
export type StageType = 'DOWNLOAD' | 'AUDIO_EXTRACT' | 'TRANSCRIBE'
export type TaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type StageStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'SKIPPED' | 'FAILED'

export interface StageView {
  type: StageType
  status: StageStatus
  detail: string | null
  artifactName: string | null
}

export interface ArtifactView {
  name: string
  sizeBytes: number
}

export interface TaskView {
  id: string
  title: string
  sourceType: 'URL' | 'UPLOAD'
  sourceRef: string | null
  status: TaskStatus
  errorMessage: string | null
  createdAt: string | null
  startedAt: string | null
  finishedAt: string | null
  stages: StageView[]
  artifacts: ArtifactView[]
}

export interface TaskPage {
  items: TaskView[]
  total: number
  page: number
  size: number
}

export interface ToolHealth {
  name: string
  ok: boolean
  version: string
}
```

`frontend/src/api/client.ts`：

```ts
import type { TaskPage, TaskView, ToolHealth } from './types'

const TOKEN_KEY = 'wave-miner-token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(t: string) {
  localStorage.setItem(TOKEN_KEY, t)
}

function headers(): Record<string, string> {
  const h: Record<string, string> = {}
  const t = getToken()
  if (t) h['X-Access-Token'] = t
  return h
}

async function parse<T>(resp: Response): Promise<T> {
  if (!resp.ok) {
    let message = `HTTP ${resp.status}`
    try {
      const body = await resp.json()
      if (body?.message) message = body.message
    } catch { /* 非 JSON 错误体 */ }
    throw new Error(message)
  }
  return resp.json() as Promise<T>
}

export const api = {
  createTask(form: FormData): Promise<TaskView> {
    return fetch('/api/tasks', { method: 'POST', headers: headers(), body: form }).then(parse)
  },
  listTasks(status: string | null, page: number, size: number): Promise<TaskPage> {
    const q = new URLSearchParams({ page: String(page), size: String(size) })
    if (status) q.set('status', status)
    return fetch(`/api/tasks?${q}`, { headers: headers() }).then(parse)
  },
  getTask(id: string): Promise<TaskView> {
    return fetch(`/api/tasks/${id}`, { headers: headers() }).then(parse)
  },
  retryTask(id: string): Promise<TaskView> {
    return fetch(`/api/tasks/${id}/retry`, { method: 'POST', headers: headers() }).then(parse)
  },
  cancelTask(id: string): Promise<TaskView> {
    return fetch(`/api/tasks/${id}/cancel`, { method: 'POST', headers: headers() }).then(parse)
  },
  deleteTask(id: string): Promise<void> {
    return fetch(`/api/tasks/${id}`, { method: 'DELETE', headers: headers() }).then(parse)
  },
  toolsHealth(): Promise<ToolHealth[]> {
    return fetch('/api/tools/health', { headers: headers() }).then(parse)
  },
  artifactUrl(taskId: string, name: string): string {
    const t = getToken()
    return `/api/tasks/${taskId}/artifacts/${encodeURIComponent(name)}${t ? `?token=${encodeURIComponent(t)}` : ''}`
  },
}
```

- [ ] **Step 3: utils + vitest（先写测试）**

`frontend/src/utils/format.ts`：

```ts
export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

export function formatTime(iso: string | null): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  return d.toLocaleString()
}

export const STAGE_LABELS: Record<string, string> = {
  DOWNLOAD: '下载',
  AUDIO_EXTRACT: '提取音频',
  TRANSCRIBE: '转写',
}

export const STATUS_LABELS: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '进行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  DONE: '完成',
  SKIPPED: '跳过',
}

export const STATUS_TAG_TYPES: Record<string, string> = {
  PENDING: 'info',
  RUNNING: 'primary',
  COMPLETED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
  DONE: 'success',
  SKIPPED: 'info',
}
```

`frontend/src/utils/format.test.ts`：

```ts
import { describe, expect, it } from 'vitest'
import { formatBytes, formatTime } from './format'

describe('formatBytes', () => {
  it('formats bytes', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
  })
  it('formats invalid', () => {
    expect(formatBytes(-1)).toBe('-')
    expect(formatBytes(NaN)).toBe('-')
  })
})

describe('formatTime', () => {
  it('returns dash for null', () => {
    expect(formatTime(null)).toBe('-')
  })
  it('parses iso', () => {
    expect(formatTime('2026-10-21T00:00:00Z')).not.toBe('-')
  })
})
```

`frontend/package.json` 的 `"scripts"` 增加：`"test": "vitest run"`。

Run: `cd frontend && npm run test`
Expected: 4 个断言组全部通过

- [ ] **Step 4: main.ts / router / App.vue**

`frontend/src/main.ts`：

```ts
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

createApp(App).use(ElementPlus).use(router).mount('#app')
```

`frontend/src/router.ts`：

```ts
import { createRouter, createWebHistory } from 'vue-router'
import NewTaskView from './views/NewTaskView.vue'
import TaskListView from './views/TaskListView.vue'
import TaskDetailView from './views/TaskDetailView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: NewTaskView },
    { path: '/tasks', component: TaskListView },
    { path: '/tasks/:id', component: TaskDetailView },
  ],
})
```

`frontend/src/App.vue` 全量替换：

```vue
<script setup lang="ts">
import { VideoCamera } from '@element-plus/icons-vue'
</script>

<template>
  <el-container style="min-height: 100vh">
    <el-header style="border-bottom: 1px solid #eee; display: flex; align-items: center; gap: 16px">
      <el-icon :size="22"><VideoCamera /></el-icon>
      <span style="font-size: 18px; font-weight: 600">wave-miner</span>
      <el-menu mode="horizontal" router :default-active="$route.path" style="flex: 1; border-bottom: none">
        <el-menu-item index="/">新建任务</el-menu-item>
        <el-menu-item index="/tasks">任务列表</el-menu-item>
      </el-menu>
    </el-header>
    <el-main style="max-width: 1080px; margin: 0 auto; width: 100%">
      <router-view />
    </el-main>
  </el-container>
</template>
```

- [ ] **Step 5: 三个视图**

`frontend/src/views/NewTaskView.vue`：

```vue
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { api, getToken, setToken } from '../api/client'
import type { ToolHealth } from '../api/types'

const router = useRouter()
const sourceMode = ref<'url' | 'upload'>('url')
const url = ref('')
const file = ref<File | null>(null)
const stages = ref<string[]>(['DOWNLOAD'])
const submitting = ref(false)
const tools = ref<ToolHealth[]>([])
const tokenInput = ref(getToken())

const needsUrl = computed(() => stages.value.includes('DOWNLOAD'))

// 切换输入模式时清理不适用的勾选
watch(sourceMode, (mode) => {
  if (mode === 'upload') {
    stages.value = stages.value.filter((s) => s !== 'DOWNLOAD')
  } else if (stages.value.length === 0) {
    stages.value = ['DOWNLOAD']
  }
})

function onFileChange(f: any) {
  file.value = f.raw as File
}

function validate(): string | null {
  if (sourceMode.value === 'url') {
    if (!url.value.trim()) return '请输入视频链接'
    if (!stages.value.includes('DOWNLOAD')) return '使用链接时必须勾选"下载"'
  } else {
    if (!file.value) return '请选择要上传的文件'
    if (stages.value.includes('DOWNLOAD')) return '上传文件时不能勾选"下载"'
  }
  if (stages.value.length === 0) return '请至少选择一个功能'
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  const form = new FormData()
  if (sourceMode.value === 'url') {
    form.set('url', url.value.trim())
  } else {
    form.set('file', file.value!)
  }
  form.set('stages', stages.value.join(','))
  submitting.value = true
  try {
    const t = await api.createTask(form)
    ElMessage.success('任务已创建')
    router.push(`/tasks/${t.id}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

async function saveToken() {
  setToken(tokenInput.value.trim())
  ElMessage.success('令牌已保存')
  await loadTools()
}

async function loadTools() {
  try {
    tools.value = await api.toolsHealth()
  } catch {
    tools.value = []
  }
}

onMounted(loadTools)
</script>

<template>
  <div>
    <el-card>
      <template #header>新建任务</template>
      <el-alert
        v-if="tools.length > 0 && tools.some((t) => !t.ok)"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
        :title="'外部工具不可用：' + tools.filter((t) => !t.ok).map((t) => t.name).join('、') + '，请先安装'"
      />
      <el-radio-group v-model="sourceMode" style="margin-bottom: 16px">
        <el-radio-button value="url">视频链接</el-radio-button>
        <el-radio-button value="upload">上传文件</el-radio-button>
      </el-radio-group>

      <el-input
        v-if="sourceMode === 'url'"
        v-model="url"
        placeholder="粘贴视频页面链接或文件直链 https://..."
        clearable
        style="margin-bottom: 16px"
      />
      <el-upload
        v-else
        :auto-upload="false"
        :limit="1"
        :on-change="onFileChange"
        drag
        style="margin-bottom: 16px"
      >
        <el-icon size="40"><UploadFilled /></el-icon>
        <div>拖拽视频/音频文件到这里，或点击选择</div>
      </el-upload>

      <div style="margin-bottom: 8px; color: #909399; font-size: 13px">选择要执行的功能（可组合）：</div>
      <el-checkbox-group v-model="stages" style="margin-bottom: 16px">
        <el-checkbox value="DOWNLOAD" :disabled="sourceMode === 'upload'">下载视频（输入：链接）</el-checkbox>
        <el-checkbox value="AUDIO_EXTRACT">提取音频（输入：视频）</el-checkbox>
      </el-checkbox-group>

      <el-button type="primary" :loading="submitting" @click="submit">创建任务</el-button>
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header>访问令牌（服务端启用时需要）</template>
      <el-space>
        <el-input v-model="tokenInput" placeholder="X-Access-Token（可选）" style="width: 320px" show-password />
        <el-button @click="saveToken">保存</el-button>
      </el-space>
      <div v-if="tools.length" style="margin-top: 12px; color: #909399; font-size: 13px">
        外部工具：
        <el-tag v-for="t in tools" :key="t.name" :type="t.ok ? 'success' : 'danger'" style="margin-right: 8px">
          {{ t.name }} {{ t.ok ? 'OK' : '不可用' }}
        </el-tag>
      </div>
    </el-card>
  </div>
</template>
```

`frontend/src/views/TaskListView.vue`：

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { TaskView } from '../api/types'
import { STAGE_LABELS, STATUS_LABELS, STATUS_TAG_TYPES, formatTime } from '../utils/format'

const router = useRouter()
const items = ref<TaskView[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
let timer: number | undefined

async function load() {
  loading.value = true
  try {
    const p = await api.listTasks(null, page.value - 1, size)
    items.value = p.items
    total.value = p.total
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function retry(t: TaskView) {
  try {
    await api.retryTask(t.id)
    ElMessage.success('已重新排队')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function cancel(t: TaskView) {
  try {
    await api.cancelTask(t.id)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(t: TaskView) {
  await ElMessageBox.confirm('删除任务及其所有产物文件？', '确认', { type: 'warning' })
  try {
    await api.deleteTask(t.id)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 3000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div>
    <el-table
      :data="items"
      v-loading="loading"
      @row-click="(r: TaskView) => router.push(`/tasks/${r.id}`)"
      style="cursor: pointer"
    >
      <el-table-column prop="title" label="任务" min-width="220" show-overflow-tooltip />
      <el-table-column label="功能" width="200">
        <template #default="{ row }">
          <el-tag v-for="s in row.stages" :key="s.type" size="small" style="margin-right: 4px">
            {{ STAGE_LABELS[s.type] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="(STATUS_TAG_TYPES[row.status] as any) || 'info'">{{ STATUS_LABELS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" @click.stop="retry(row)" :disabled="row.status !== 'FAILED' && row.status !== 'CANCELLED'">重试</el-button>
          <el-button size="small" @click.stop="cancel(row)" :disabled="row.status !== 'RUNNING' && row.status !== 'PENDING'">取消</el-button>
          <el-button size="small" type="danger" @click.stop="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="size"
      style="margin-top: 16px; justify-content: flex-end"
      @current-change="load"
    />
  </div>
</template>
```

`frontend/src/views/TaskDetailView.vue`：

```vue
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { TaskView } from '../api/types'
import { STAGE_LABELS, STATUS_LABELS, STATUS_TAG_TYPES, formatBytes, formatTime } from '../utils/format'

const route = useRoute()
const id = route.params.id as string
const task = ref<TaskView | null>(null)
let timer: number | undefined

const isRunning = computed(() =>
  task.value?.status === 'RUNNING' || task.value?.status === 'PENDING')

const playableArtifact = computed(() => {
  if (!task.value) return null
  return task.value.artifacts.find((a) => /\.(mp4|mkv|webm|mov|m4v|mp3|m4a|wav|flac|ogg)$/i.test(a.name)) ?? null
})

const playableUrl = computed(() => {
  if (!playableArtifact.value) return ''
  return api.artifactUrl(id, playableArtifact.value.name)
})

async function load() {
  try {
    task.value = await api.getTask(id)
  } catch (e) {
    ElMessage.error((e as Error).message)
    clearInterval(timer)
  }
}

async function retry() {
  try {
    await api.retryTask(id)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function cancel() {
  try {
    await api.cancelTask(id)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 2000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div v-if="task">
    <el-page-header :content="task.title" @back="$router.push('/tasks')" style="margin-bottom: 16px" />

    <el-descriptions :column="3" border>
      <el-descriptions-item label="状态">
        <el-tag :type="(STATUS_TAG_TYPES[task.status] as any) || 'info'">{{ STATUS_LABELS[task.status] }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="来源">{{ task.sourceType === 'URL' ? '链接' : '上传' }}</el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ formatTime(task.createdAt) }}</el-descriptions-item>
      <el-descriptions-item label="完成时间">{{ formatTime(task.finishedAt) }}</el-descriptions-item>
      <el-descriptions-item label="来源地址" :span="2">
        <span style="word-break: break-all">{{ task.sourceRef }}</span>
      </el-descriptions-item>
    </el-descriptions>

    <el-alert
      v-if="task.errorMessage"
      type="error"
      :title="task.errorMessage"
      show-icon
      :closable="false"
      style="margin-top: 12px"
    />

    <el-card style="margin-top: 16px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>执行进度</span>
          <el-space>
            <el-button size="small" :disabled="task.status !== 'FAILED' && task.status !== 'CANCELLED'" @click="retry">重试</el-button>
            <el-button size="small" :disabled="!isRunning" @click="cancel">取消</el-button>
          </el-space>
        </div>
      </template>
      <el-steps direction="vertical">
        <el-step
          v-for="s in task.stages"
          :key="s.type"
          :title="STAGE_LABELS[s.type]"
          :status="s.status === 'DONE' ? 'success' : s.status === 'FAILED' ? 'error' : s.status === 'RUNNING' ? 'process' : s.status === 'SKIPPED' ? 'wait' : 'wait'"
          :description="(s.detail ?? '') + (s.artifactName ? `（产物: ${s.artifactName}）` : '')"
        />
      </el-steps>
    </el-card>

    <el-card v-if="playableArtifact" style="margin-top: 16px">
      <template #header>在线预览 · {{ playableArtifact.name }}（{{ formatBytes(playableArtifact.sizeBytes) }}）</template>
      <video
        v-if="/\.(mp4|mkv|webm|mov|m4v)$/i.test(playableArtifact.name)"
        :src="playableUrl"
        controls
        style="width: 100%; max-height: 480px"
      />
      <audio v-else :src="playableUrl" controls style="width: 100%" />
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header>产物下载</template>
      <el-empty v-if="task.artifacts.length === 0" description="暂无产物" />
      <el-table v-else :data="task.artifacts">
        <el-table-column prop="name" label="文件" min-width="240" />
        <el-table-column label="大小" width="120">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" type="primary">
              <a :href="api.artifactUrl(id, row.name)" :download="row.name" style="color: inherit; text-decoration: none">下载</a>
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
```

- [ ] **Step 6: 构建验证**

Run: `cd frontend && npm run test && npm run build`
Expected: vitest 全过；`vite build` 产出 `dist/`

- [ ] **Step 7: Commit**

```bash
git add frontend && git commit -m "feat: Vue 3 前端（新建任务/任务列表/任务详情）"
```

---

### Task 11: 静态托管与打包（jar 内嵌前端）+ README

**Files:**
- Create: `README.md`、`backend/src/main/java/com/waveminer/waveminer/config/SpaWebConfig.java`
- Modify: `backend/pom.xml`
- Verify: `frontend/dist` 存在（Task 10 产物）

**Interfaces:**
- Produces: `cd frontend && npm run build && cd ../backend && ./mvnw -q package` 产出单 jar；`java -jar` 同时服务 UI（`http://127.0.0.1:8080/`）与 API

- [ ] **Step 1: SPA 回退配置**

`backend/src/main/java/com/waveminer/waveminer/config/SpaWebConfig.java`（前端是 history 路由，刷新 `/tasks/x` 需回退 index.html）：

```java
package com.waveminer.waveminer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        // API 路径不受影响（404 由异常处理器负责）；其余回退 index.html
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
```

- [ ] **Step 2: pom.xml 增加前端构建插件**

`backend/pom.xml` `<build><plugins>` 内追加（`npm run build` 在 Maven 打包时自动执行；无 node 环境时用 `-Dskip.frontend` 跳过）：

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <id>build-frontend</id>
            <phase>generate-resources</phase>
            <goals><goal>exec</goal></goals>
            <configuration>
                <skip>${skip.frontend}</skip>
                <workingDirectory>${project.basedir}/../frontend</workingDirectory>
                <executable>npm</executable>
                <arguments>
                    <argument>run</argument>
                    <argument>build</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-resources-plugin</artifactId>
    <executions>
        <execution>
            <id>copy-frontend</id>
            <phase>process-resources</phase>
            <goals><goal>copy-resources</goal></goals>
            <configuration>
                <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                <resources>
                    <resource>
                        <directory>${project.basedir}/../frontend/dist</directory>
                    </resource>
                </resources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 3: 验证打包与启动**

```bash
cd frontend && npm run build && cd ../backend && ./mvnw -q package -DskipTests
java -jar target/wave-miner-0.0.1-SNAPSHOT.jar &
sleep 5
curl -sf http://127.0.0.1:8080/api/tools/health
curl -sf http://127.0.0.1:8080/ | head -5   # 应返回 index.html
kill %1
```

Expected: health 返回 JSON 数组；根路径返回 HTML。

- [ ] **Step 4: README.md**

```markdown
# wave-miner

个人/小团队的音视频内容挖掘工具：视频下载、音频提取、带时间戳的文字转写（转写引擎在后续版本提供）。功能可独立使用，也可组合成流水线。

## 依赖

- JDK 21、Node.js 20+、Maven（或使用自带 mvnw）
- ffmpeg / ffprobe（`brew install ffmpeg`）
- yt-dlp（`brew install yt-dlp`）

## 开发运行

```bash
# 后端（127.0.0.1:8080）
cd backend && ./mvnw spring-boot:run

# 前端（5173，代理 /api 到 8080）
cd frontend && npm install && npm run dev
```

## 打包运行（单 jar 内嵌前端）

```bash
cd frontend && npm install && npm run build
cd ../backend && ./mvnw -q package
java -jar backend/target/wave-miner-0.0.1-SNAPSHOT.jar
# 打开 http://127.0.0.1:8080
```

## 配置（backend/src/main/resources/application.yml 或环境变量）

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.data-dir` | `./data` | 任务数据目录（SQLite + 产物） |
| `app.max-concurrent-tasks` | `2` | 同时执行的任务数 |
| `app.ffmpeg-path` / `app.ffprobe-path` / `app.yt-dlp-path` | PATH | 外部工具路径 |
| `app.process-timeout-seconds` | `1800` | 外部进程超时 |
| `app.access-token` | 空 | 非空时所有 API 需带 `X-Access-Token` 头（或 `?token=`） |

环境变量映射：`APP_DATA_DIR`、`APP_MAX_CONCURRENT_TASKS`、`APP_FFMPEG_PATH`、`APP_YT_DLP_PATH`、`APP_ACCESS_TOKEN` 等。

## 测试

```bash
cd backend && ./mvnw test      # 后端全部单元/集成测试（不依赖真实 ffmpeg/yt-dlp）
cd frontend && npm run test    # 前端 vitest
```
```

- [ ] **Step 5: Commit**

```bash
git add README.md backend && git commit -m "chore: jar 内嵌前端打包与 README"
```

---

### Task 12: 全链路手工验收（真实工具）

**Files:** 无新增（验收任务）

**Interfaces:**
- Consumes: 完整系统（Task 1-11）

- [ ] **Step 1: 本机准备外部工具**

```bash
which ffmpeg ffprobe yt-dlp || brew install ffmpeg yt-dlp
```

- [ ] **Step 2: 启动系统**

```bash
cd backend && ./mvnw spring-boot:run
# 另开终端
cd frontend && npm run dev
# 打开 http://localhost:5173
```

- [ ] **Step 3: 手工验收清单**

1. 工具健康：新建任务页应显示 ffmpeg/ffprobe/yt-dlp 三个绿色 OK 标签
2. 上传视频 → 仅勾选"提取音频" → 任务 COMPLETED → 详情页在线播放 audio.m4a + 下载
3. 粘贴一个小的直链 mp4（如 `https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4`）→ 勾选"下载+提取音频" → COMPLETED → 两个产物可下载/播放
4. 粘贴一个 B 站视频页面链接 → 勾选"下载" → COMPLETED → source.mp4 可下载（B 站若要求登录/风控，允许 FAILED 且错误信息可读——记录现象即可，非阻塞）
5. 列表页：状态/重试/取消/删除按钮按状态正确启用
6. 重启后端进程 → 之前 RUNNING 任务显示"应用重启导致中断，可重试"，重试可用
7. 设置 `APP_ACCESS_TOKEN=abc123` 重启 → 未配置令牌时 API 401，前端保存令牌后正常

- [ ] **Step 4: 验收记录 Commit（如有调整）**

```bash
git add -A && git commit -m "chore: M1+M2 验收修正"
```

---

## 计划 1 覆盖的规格条目

- §2 技术栈（Java 21/Spring Boot 4.1/SQLite/Vue 3/虚拟线程/127.0.0.1 绑定）→ Task 1/7/10
- §3 架构分层（api/pipeline/stage/media/store）→ Task 2-9 的包结构
- §4 流水线模型（三 Stage 中的前两个、组合矩阵、输入校验、产物目录）→ Task 6/7/8
- §6 任务模型与执行（状态机、虚拟线程执行器+Semaphore、失败保留产物、retry 续跑、重启恢复）→ Task 2/7/9
- §7 HTTP API（除 engines/settings/SSE 外全部；访问令牌机制）→ Task 9
- §8 外部工具封装（ffmpeg/yt-dlp/健康检查/直链回退/超时/stderr 摘要）→ Task 4/5/8
- §10 错误处理（stage 失败隔离、中文可读错误）→ Task 7/9
- §12 M1+M2 验收 → Task 12

**后续计划（另行编写）**：计划 2 = 转写引擎层（§5 MiniMax/阿里云 + TRANSCRIBE stage + 三种产物格式）+ SSE + 时间轴对照 UI（§9）+ M3/M4/M5；计划 3 = 超长音频切分 + 设置页 + OpenAI 兼容模板引擎 + 完整访问令牌管理（M6）。
