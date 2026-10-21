# wave-miner 计划 2（M3+M4+M5）：转写引擎 + TRANSCRIBE + SSE + 时间轴对照 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 wave-miner 增加"转写"能力：可插拔转写引擎层（MiniMax + 阿里云百炼 filetrans）、超长音频静音切分、三种转写产物（json/srt/txt）、SSE 实时进度、任务详情时间轴对照 UI。

**Architecture:** 引擎层为 SPI（`TranscriptionEngine`），所有引擎内部完成归一化为统一句级 `Segment{startMs,endMs,text,speaker}`，原始响应存档 `engine_raw/`；`TranscribeStage` 负责输入解析、超限时静音切分、产物落盘；进度经内存 `TaskEventPublisher` 广播到 `GET /api/tasks/{id}/events`（SSE）；前端在详情页做媒体播放器↔分段列表双向联动。

**Tech Stack:** Java 21（虚拟线程）、Spring Boot 4.1.1（Jackson 3 = `tools.jackson.*`）、SQLite、`java.net.http.HttpClient`、WireMock（test）、Vue 3 + TS + Element Plus（现有工程上扩展）。

**Spec:** `docs/superpowers/specs/2026-10-21-wave-miner-design.md`（§5 引擎层、§4.3 产物、§7 engines/settings/events、§9 前端、§11 测试、§13 已核对项、§14 跨平台）；另见 `docs/superpowers/HANDOFF-PLAN2.md`（§3 范围、§0 约定）。

## 官方 API 核对结论（2026-10-21 已核对，实现以此为准）

### MiniMax 语音识别（国际站 + 中国站字段完全一致）

来源：https://platform.minimax.io/docs/api-reference/speech-to-text 与 https://platform.minimaxi.com/docs/api-reference/speech-to-text（两站 OpenAPI 内容相同，仅 server 不同）。

- `POST {baseUrl}/v1/speech_to_text`，`Authorization: Bearer {key}`，`Content-Type: multipart/form-data`
- **请求头** `language`（可选，BCP-47：zh/yue/en/ja/ko/th/vi/id/ms/fil/ar/tr/fr/de/es/it/pt/pl/ru/uk；**不传或空 = 混合语种**）
- **multipart 字段**：`model`（必填，`asr-1.0`）、`file`（必填）、`response_format=verbose_json`、`timestamp_level=sentence`
- **限制**：时长 ≤500 秒（超出 400，不截断）；大小 ≤50MB（超出 413）；格式 wav/aiff/flac/alac(m4a)/mp3/aac/opus/ogg；不支持裸 PCM
- **verbose_json 响应**：`{text, duration(秒,浮点), n_speakers, segments:[{id:int, start:秒, end:秒, speaker:"S1", text}], trace_id}`
- **错误体**（OpenAI 风格，HTTP 状态码为真实码）：`{type:"error", error:{type, message, http_code}, request_id}`
- **baseUrl**：国际站 `https://api.minimax.io`；**中国站 `https://api.minimax.cn`**（中国站文档 platform.minimaxi.com 的 OpenAPI `servers` 字段；key 管理在 platform.minimax.cn）
- ⚠️ **Ruling（修正交接文档）**：交接文档 §3 写中国站为 `https://api.minimaxi.com`；官方中国站 OpenAPI 的 server 是 `https://api.minimax.cn`。**默认 baseUrl 采用 `https://api.minimax.cn`**，可配置。

### 阿里云百炼录音文件转写（DashScope 异步 filetrans）

来源：https://help.aliyun.com/zh/model-studio/paraformer-recorded-speech-recognition-restful-api 、 https://help.aliyun.com/zh/model-studio/non-realtime-speech-recognition-user-guide 、官方 SDK 源码 https://github.com/dashscope/dashscope-sdk-java `OSSUtils.java`（上传凭证权威实现）。

- **baseUrl**：默认 `https://dashscope.aliyuncs.com/api/v1`；支持业务空间专属域名 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1`（可配置，存完整 base，含 `/api/v1`）
- **① 获取上传凭证**（本地文件必须走此流程，filetrans API 只收公网 URL / `oss://` 临时 URL）：
  - `GET {base}/uploads?action=getPolicy&model={model}`，`Authorization: Bearer {api-key}`
  - 响应：`{"output":{"data":{"upload_host","oss_access_key_id","signature","policy","upload_dir","x_oss_object_acl","x_oss_forbid_overwrite"}},"request_id"}`
- **② 上传到 OSS**：`POST {upload_host}`，multipart/form-data，字段（官方 SDK 顺序）：`OSSAccessKeyId`、`Signature`、`policy`、`key`（=`{upload_dir}/{文件名}`）、`x-oss-object-acl`、`x-oss-forbid-overwrite`、`success_action_status=200`、`x-oss-content-type`、`file`；成功即 HTTP 200
- **③ 文件 URL**：`oss://{key}`，可直接放入 `file_urls`（官方 REST 文档明确支持 `oss://` 前缀临时 URL）
- **④ 提交任务**：`POST {base}/services/audio/asr/transcription`，头 `Authorization: Bearer`、`Content-Type: application/json`、`X-DashScope-Async: enable`
  - body：`{"model":"qwen-audio-3.0-asr-flash-filetrans","input":{"file_urls":["oss://..."]},"parameters":{...}}`
  - parameters：`channel_id:[0]`、`language_hints:["zh","en"]`（用户指南示例对 qwen-audio filetrans 适用）、`diarization_enabled:true`（仅单声道，建议 ≤2h）、`speaker_count`(2-100)
  - 响应：`{"output":{"task_id","task_status":"PENDING"},"request_id"}`
- **⑤ 轮询**：`POST {base}/tasks/{task_id}`（Bearer 头；用户指南新文档亦示 GET，两者官方皆认可——**Ruling：用 POST**，与 paraformer REST 文档和官方 demo 一致）
  - 响应：`{"output":{"task_status":"PENDING|RUNNING|SUCCEEDED|FAILED","results":[{"file_url","transcription_url","subtask_status","code","message"}],"task_metrics":{...}}}`
  - **总状态 SUCCEEDED 仍须检查 `results[].subtask_status` 与 `code`/`message`**
- **⑥ 结果**：`transcription_url` 24h 有效，JSON：`{file_url, properties:{original_duration_in_milliseconds,...}, transcripts:[{channel_id, text, sentences:[{begin_time, end_time(毫秒), text, sentence_id, speaker_id?, words:[...]}]}]}`
- **限制**：单文件 ≤12 小时 / ≤2GB；模型（北京）：`qwen-audio-3.0-asr-flash-filetrans`（默认）、`qwen-audio-3.1-asr-flash-filetrans`、`paraformer-v2`（备选，`app.aliyun-model` 可配）
- 上传凭证接口限流 100 QPS（个人使用足够）

## Rulings（自行裁定的决策记录）

1. MiniMax 中国站 baseUrl 默认 `https://api.minimax.cn`（修正交接文档的 `api.minimaxi.com`）。
2. 阿里云轮询接口用 POST（paraformer REST 文档 + 官方示例；用户指南的 GET 亦官方）。
3. 任务列扩展用 `SchemaMigrator`（ApplicationRunner，`PRAGMA table_info` 检查后 `ALTER TABLE ADD COLUMN`），兼容已存在的开发库；`schema.sql` 保持幂等。
4. API Key 解析顺序：SQLite `app_setting`（非空优先）→ 环境变量（`WAVEMINER_MINIMAX_API_KEY`；阿里云 `WAVEMINER_ALIYUN_API_KEY` 兼容 `DASHSCOPE_API_KEY`）→ 无。设置页 PUT 空值 = 清除。
5. 统一句级 `speaker` 归一化为 `Integer`：MiniMax `"S1"`→1（去非数字）；阿里云 `speaker_id` 原样；缺省 null。合并时仅当两者非空且不相等视为冲突。
6. TRANSCRIBE 输入为视频且无上游音频时，stage 内部先 `ffmpeg.extractAudio` 到 `audio.m4a`（规格 §4.2 组合①③）。
7. `transcript.txt` 格式：每段一行 `[mm:ss] text`；SRT 不带说话人前缀；`transcript.json` 含 `segments/engine/language/durationMs`。
8. 引擎取消：`ProgressCallback` 增加默认方法 `cancelled()`（stage 实现 = 检查取消标记）；引擎在长轮询循环中检查并抛 `EngineCancelledException extends EngineException`；stage 捕获后转 `TaskCancelledException`。
9. engine_raw 下载路径带子目录（`engine_raw/xxx.json`）：后端路由改 `/{name:.+}`，前端 URL 按 `/` 分段逐段 encodeURIComponent（避免 Tomcat 拒绝 %2F）。
10. 分片文件放 `data/tasks/{id}/chunks/`（非产物），成功归并后删除，失败保留便于排查。
11. 长轮询超时 `app.transcribe-timeout-seconds` 默认 7200s；引擎 HTTP 请求超时 `app.engine-http-timeout-seconds` 默认 600s；429/5xx/IOException 重试 `app.engine-retry-attempts` 默认 3 次（线性退避 1s/2s，测试可把基准调成 1ms）。
12. SSE 终态（任务 COMPLETED/FAILED/CANCELLED）推送后服务端主动 complete 该任务的 emitter；前端收到终态事件刷新后关闭 EventSource。轮询兜底仅在 SSE 断开期间启用。
13. 新建任务 TRANSCRIBE 默认：engineId 缺省 `minimax`、language 缺省 `auto`、diarize 缺省 false；engineId 对 registry 校验、language 对该引擎 `languages` 校验（无效 → 400 中文错误）。
14. WireMock 用 `org.wiremock:wiremock-standalone:3.13.1`（test scope；拉取失败则用 `3.9.2`）。

## Global Constraints

- Java 21 + 虚拟线程；**不使用 Lombok**。
- Spring Boot 4.1.1，JSON 库为 Jackson 3：`tools.jackson.databind.ObjectMapper/JsonNode`（与 fasterxml 包名不同，注意 import）。
- 用户可见错误信息一律中文；引擎错误提示需包含"原始响应已存档到 engine_raw/"类说明。
- 双平台（Windows 验证 / Linux 部署）：新测试禁止仅依赖 Unix 命令；外部进程一律走 `ProcessRunner`（含 cancelSignal 3 参重载）；ffmpeg/ffprobe 路径走 `AppProperties`。
- 通用验证命令：`cd backend && ./mvnw -q test`；`cd frontend && npm run test && npm run build`。
- 每任务以 conventional commit 收尾，一次提交一个可运行状态；全部新文件 UTF-8；前端组件 `<script setup lang="ts">`。
- 现有代码关键接口（计划 1 产物，直接使用）：`ProcessRunner.run(List<String>, Duration[, BooleanSupplier])`；`FfmpegService(Runner, AppProperties)`（`Runner.run(cmd, timeout, cancelSignal)` 有 default 2 参重载；已有 `probeDurationSeconds/extractAudio/hasAudioStream`）；`ArtifactStore(taskDir/findSourceFile/isAllowedArtifactName/listArtifacts)`；`TaskContext(taskId/dir/sourceUrl/uploadedFile/cancelRequested/putArtifact/artifactOf)`；`TaskRunner`（取消标记/in-flight 守卫/retry 续跑）；`StageHandler{type(), run(TaskContext)}`；`TaskCancelledException`（无参构造，pipeline 包）；`ApiExceptionHandler`（IllegalArgumentException→400、IllegalStateException→409、Exception→500）；`AccessTokenInterceptor`（`X-Access-Token` 或 `?token=`）。

---

### Task 1: 设置存储 app_setting + GET/PUT /api/settings（脱敏）

**Files:**
- Modify: `backend/src/main/resources/schema.sql`、`backend/src/main/java/com/waveminer/waveminer/config/WebConfig.java`（CORS 加 PUT）
- Create: `backend/src/main/java/com/waveminer/waveminer/store/SettingService.java`、`api/SettingController.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/store/SettingServiceTest.java`、`api/SettingApiTest.java`

**Interfaces:**
- Produces:
  - 表 `app_setting(key TEXT PRIMARY KEY, value TEXT NOT NULL)`（schema.sql，幂等）
  - `SettingService`：`String get(String key)`（null=无）、`void set(String key, String value)`（blank→删除）、`Map<String,String> getAll()`；已知键 `minimax.apiKey`、`aliyun.apiKey`
  - `GET /api/settings` → `[{"key":"minimax.apiKey","value":"sk****abcd","configured":true}]`（含环境变量兜底；record `SettingView(String key, String value, boolean configured)`）
  - `PUT /api/settings` body `{"key":"...","value":"..."}` → 脱敏后的 SettingView；未知 key → 400（中文）
  - 脱敏规则：长度>10 保留前 2 后 4，中间 `****`；否则 `****`
  - 后续任务依赖的解析顺序：设置表值（非空）→ 环境变量 `WAVEMINER_MINIMAX_API_KEY` / `WAVEMINER_ALIYUN_API_KEY`（兼容 `DASHSCOPE_API_KEY`）→ null

- [ ] **Step 1: schema.sql 追加**

```sql
CREATE TABLE IF NOT EXISTS app_setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
```

- [ ] **Step 2: 写 SettingService 测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/store/SettingServiceTest.java`：

```java
package com.waveminer.waveminer.store;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SettingServiceTest {

    @TempDir
    Path tmp;

    private SettingService service;

    @BeforeEach
    void setUp() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("s.db")));
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        service = new SettingService(jdbc);
    }

    @Test
    void setGetAndOverwrite() {
        assertThat(service.get("minimax.apiKey")).isNull();
        service.set("minimax.apiKey", "sk-1234567890abcd");
        assertThat(service.get("minimax.apiKey")).isEqualTo("sk-1234567890abcd");
        service.set("minimax.apiKey", "sk-ffff");
        assertThat(service.get("minimax.apiKey")).isEqualTo("sk-ffff");
        assertThat(service.getAll()).containsEntry("minimax.apiKey", "sk-ffff");
    }

    @Test
    void blankValueDeletes() {
        service.set("aliyun.apiKey", "sk-1");
        service.set("aliyun.apiKey", "  ");
        assertThat(service.get("aliyun.apiKey")).isNull();
        assertThat(service.getAll()).isEmpty();
    }
}
```

- [ ] **Step 3: 实现 SettingService**

`store/SettingService.java`：

```java
package com.waveminer.waveminer.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingService {

    private final JdbcTemplate jdbc;

    public SettingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String get(String key) {
        List<String> rows = jdbc.query("SELECT value FROM app_setting WHERE key = ?",
                (rs, i) -> rs.getString(1), key);
        return rows.isEmpty() || rows.get(0).isBlank() ? null : rows.get(0);
    }

    public Map<String, String> getAll() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM app_setting",
                rs -> out.put(rs.getString(1), rs.getString(2)));
        return out;
    }

    @Transactional
    public void set(String key, String value) {
        if (value == null || value.isBlank()) {
            jdbc.update("DELETE FROM app_setting WHERE key = ?", key);
            return;
        }
        jdbc.update("INSERT INTO app_setting(key,value) VALUES(?,?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value.trim());
    }
}
```

- [ ] **Step 4: 写 SettingApiTest（先失败）**

`backend/src/test/java/com/waveminer/waveminer/api/SettingApiTest.java`：

```java
package com.waveminer.waveminer.api;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettingApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
    }

    @Autowired MockMvc mvc;

    @Test
    void putThenGetMasksValue() throws Exception {
        mvc.perform(put("/api/settings")
                        .contentType("application/json")
                        .content("{\"key\":\"minimax.apiKey\",\"value\":\"sk-1234567890abcd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("minimax.apiKey"))
                .andExpect(jsonPath("$.value").value("sk****abcd"))
                .andExpect(jsonPath("$.configured").value(true));

        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='minimax.apiKey')].value").value("sk****abcd"))
                .andExpect(jsonPath("$[?(@.key=='minimax.apiKey')].configured").value(true));
    }

    @Test
    void blankValueClearsKey() throws Exception {
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"aliyun.apiKey\",\"value\":\"sk-1234567890abcd\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"aliyun.apiKey\",\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void unknownKeyRejected() throws Exception {
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"hacker.key\",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未知的设置项")));
    }
}
```

- [ ] **Step 5: 实现 SettingController**

`api/SettingController.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.store.SettingService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingController {

    /** 已知设置键 → 环境变量兜底（按序取第一个非空） */
    private static final Map<String, List<String>> KNOWN_KEYS = Map.of(
            "minimax.apiKey", List.of("WAVEMINER_MINIMAX_API_KEY"),
            "aliyun.apiKey", List.of("WAVEMINER_ALIYUN_API_KEY", "DASHSCOPE_API_KEY"));

    public record SettingView(String key, String value, boolean configured) {}
    private record PutRequest(String key, String value) {}

    private final SettingService settings;

    public SettingController(SettingService settings) { this.settings = settings; }

    @GetMapping
    public List<SettingView> list() {
        return KNOWN_KEYS.keySet().stream().sorted().map(k -> {
            String stored = settings.get(k);
            String effective = stored != null ? stored : envValue(k);
            return new SettingView(k, mask(effective), effective != null && !effective.isBlank());
        }).toList();
    }

    @PutMapping
    public SettingView put(@RequestBody PutRequest body) {
        if (body == null || body.key() == null || !KNOWN_KEYS.containsKey(body.key())) {
            throw new IllegalArgumentException("未知的设置项: " + (body == null ? "" : body.key()));
        }
        settings.set(body.key(), body.value() == null ? "" : body.value().trim());
        String effective = settings.get(body.key());
        return new SettingView(body.key(), mask(effective), effective != null);
    }

    private static String envValue(String key) {
        for (String env : KNOWN_KEYS.get(key)) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    static String mask(String v) {
        if (v == null || v.isBlank()) return "";
        if (v.length() <= 10) return "****";
        return v.substring(0, 2) + "****" + v.substring(v.length() - 4);
    }
}
```

- [ ] **Step 6: WebConfig CORS 允许 PUT**

`config/WebConfig.java` 中 `allowedMethods("GET", "POST", "DELETE")` 改为 `allowedMethods("GET", "POST", "PUT", "DELETE")`。

- [ ] **Step 7: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add backend && git commit -m "feat: 设置存储与脱敏的设置 API"
```

---

### Task 2: 转写引擎 SPI 类型 + EngineRegistry + GET /api/engines

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/engine/Segment.java`、`engine/EngineInfo.java`、`engine/TranscribeOptions.java`、`engine/TranscribeResult.java`、`engine/ProgressCallback.java`、`engine/TranscriptionEngine.java`、`engine/EngineException.java`、`engine/EngineCancelledException.java`、`engine/EngineRegistry.java`、`engine/EngineKeyProvider.java`、`engine/SettingsEngineKeyProvider.java`、`api/EngineController.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/engine/EngineRegistryTest.java`

**Interfaces:**
- Consumes: `SettingService`（Task 1）
- Produces（后续所有任务依赖，签名精确如下）:
  - `record Segment(long startMs, long endMs, String text, Integer speaker)`
  - `record EngineInfo(String id, String displayName, long maxDurationSec, long maxSizeBytes, boolean supportsDiarization, List<String> languages)`（languages 为 BCP-47 短码，不含 auto）
  - `record TranscribeOptions(String language, boolean diarize)` + 静态 `TranscribeOptions.of(String language, boolean diarize)`（null/blank→"auto"）
  - `record TranscribeResult(List<Segment> segments, String rawResponse)`
  - `interface ProgressCallback { void update(String message); default boolean cancelled() { return false; } static ProgressCallback noop() { return m -> {}; } }`
  - `interface TranscriptionEngine { String id(); EngineInfo info(); TranscribeResult transcribe(Path mediaFile, TranscribeOptions options, ProgressCallback progress); }`
  - `class EngineException extends RuntimeException`（`String getRawResponse()` 可 null；构造器 `(String)`、`(String, String raw)`、`(String, Throwable cause, String raw)`）
  - `class EngineCancelledException extends EngineException`（`(String msg)`）
  - `EngineRegistry`（@Component）：`EngineRegistry(ObjectProvider<TranscriptionEngine> provider)`、`List<TranscriptionEngine> all()`（`provider.orderedStream().toList()`）、`TranscriptionEngine get(String id)`（未知 → `EngineException("未知转写引擎: " + id)`）
  - `interface EngineKeyProvider { String getKey(String engineId); }`；`SettingsEngineKeyProvider`（@Component）：minimax→`minimax.apiKey`，aliyun-filetrans→`aliyun.apiKey`（环境变量兜底同 Task 1 规则）
  - `GET /api/engines` → `[{id,displayName,maxDurationSec,maxSizeBytes,supportsDiarization,languages,configured}]`（record `EngineView`，configured = key 非空；控制器构造参数用 `ObjectProvider<TranscriptionEngine>` 避免空列表注入失败）

- [ ] **Step 1: 写 EngineRegistry 测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/engine/EngineRegistryTest.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineRegistryTest {

    @SuppressWarnings("unchecked")
    private EngineRegistry registry(TranscriptionEngine... engines) {
        ObjectProvider<TranscriptionEngine> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.orderedStream()).thenReturn(List.of(engines).stream());
        return new EngineRegistry(provider);
    }

    private TranscriptionEngine fake(String id) {
        TranscriptionEngine e = Mockito.mock(TranscriptionEngine.class);
        Mockito.when(e.id()).thenReturn(id);
        return e;
    }

    @Test
    void getReturnsEngineById() {
        EngineRegistry r = registry(fake("a"), fake("b"));
        assertThat(r.get("b").id()).isEqualTo("b");
        assertThat(r.all()).hasSize(2);
    }

    @Test
    void unknownIdFailsInChinese() {
        EngineRegistry r = registry(fake("a"));
        assertThatThrownBy(() -> r.get("nope"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("未知转写引擎");
    }

    @Test
    void emptyRegistryAllowed() {
        assertThat(registry().all()).isEmpty();
    }
}
```

- [ ] **Step 2: 实现全部类型（record/接口按 Interfaces 块逐字创建）**

`engine/Segment.java`：

```java
package com.waveminer.waveminer.engine;

public record Segment(long startMs, long endMs, String text, Integer speaker) {}
```

`engine/EngineInfo.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;

public record EngineInfo(String id, String displayName, long maxDurationSec, long maxSizeBytes,
                         boolean supportsDiarization, List<String> languages) {}
```

`engine/TranscribeOptions.java`：

```java
package com.waveminer.waveminer.engine;

public record TranscribeOptions(String language, boolean diarize) {
    public static TranscribeOptions of(String language, boolean diarize) {
        return new TranscribeOptions(language == null || language.isBlank() ? "auto" : language, diarize);
    }
}
```

`engine/TranscribeResult.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;

public record TranscribeResult(List<Segment> segments, String rawResponse) {}
```

`engine/ProgressCallback.java`：

```java
package com.waveminer.waveminer.engine;

@FunctionalInterface
public interface ProgressCallback {
    void update(String message);

    default boolean cancelled() { return false; }

    static ProgressCallback noop() { return m -> {}; }
}
```

`engine/TranscriptionEngine.java`：

```java
package com.waveminer.waveminer.engine;

import java.nio.file.Path;

public interface TranscriptionEngine {
    String id();

    EngineInfo info();

    TranscribeResult transcribe(Path mediaFile, TranscribeOptions options, ProgressCallback progress);
}
```

`engine/EngineException.java`：

```java
package com.waveminer.waveminer.engine;

public class EngineException extends RuntimeException {
    private final String rawResponse;

    public EngineException(String message) { this(message, null, null); }

    public EngineException(String message, String rawResponse) { this(message, null, rawResponse); }

    public EngineException(String message, Throwable cause, String rawResponse) {
        super(message, cause);
        this.rawResponse = rawResponse;
    }

    public String getRawResponse() { return rawResponse; }
}
```

`engine/EngineCancelledException.java`：

```java
package com.waveminer.waveminer.engine;

public class EngineCancelledException extends EngineException {
    public EngineCancelledException(String message) { super(message); }
}
```

`engine/EngineRegistry.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class EngineRegistry {

    private final ObjectProvider<TranscriptionEngine> provider;

    public EngineRegistry(ObjectProvider<TranscriptionEngine> provider) { this.provider = provider; }

    public List<TranscriptionEngine> all() {
        return provider.orderedStream().toList();
    }

    public TranscriptionEngine get(String id) {
        return all().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new EngineException("未知转写引擎: " + id));
    }
}
```

`engine/EngineKeyProvider.java`：

```java
package com.waveminer.waveminer.engine;

public interface EngineKeyProvider {
    /** 无配置返回 null */
    String getKey(String engineId);
}
```

`engine/SettingsEngineKeyProvider.java`：

```java
package com.waveminer.waveminer.engine;

import com.waveminer.waveminer.store.SettingService;
import org.springframework.stereotype.Component;

@Component
public class SettingsEngineKeyProvider implements EngineKeyProvider {

    private final SettingService settings;

    public SettingsEngineKeyProvider(SettingService settings) { this.settings = settings; }

    @Override
    public String getKey(String engineId) {
        return switch (engineId) {
            case "minimax" -> firstNonBlank(settings.get("minimax.apiKey"), env("WAVEMINER_MINIMAX_API_KEY"));
            case "aliyun-filetrans" -> firstNonBlank(settings.get("aliyun.apiKey"),
                    env("WAVEMINER_ALIYUN_API_KEY"), env("DASHSCOPE_API_KEY"));
            default -> null;
        };
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
```

- [ ] **Step 3: 实现 EngineController**

`api/EngineController.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.engine.EngineInfo;
import com.waveminer.waveminer.engine.EngineKeyProvider;
import com.waveminer.waveminer.engine.TranscriptionEngine;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/engines")
public class EngineController {

    public record EngineView(String id, String displayName, long maxDurationSec, long maxSizeBytes,
                             boolean supportsDiarization, List<String> languages, boolean configured) {}

    private final List<TranscriptionEngine> engines;
    private final EngineKeyProvider keys;

    public EngineController(ObjectProvider<TranscriptionEngine> engines, EngineKeyProvider keys) {
        this.engines = engines.orderedStream().toList();
        this.keys = keys;
    }

    @GetMapping
    public List<EngineView> list() {
        return engines.stream().map(e -> {
            EngineInfo i = e.info();
            return new EngineView(i.id(), i.displayName(), i.maxDurationSec(), i.maxSizeBytes(),
                    i.supportsDiarization(), i.languages(), keys.getKey(i.id()) != null);
        }).toList();
    }
}
```

- [ ] **Step 4: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: 转写引擎 SPI 与引擎注册表、GET /api/engines"
```

---

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

### Task 4: 转写产物格式器 TranscriptFormat + SegmentMerger

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/engine/TranscriptFormat.java`、`engine/SegmentMerger.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/engine/TranscriptFormatTest.java`、`engine/SegmentMergerTest.java`

**Interfaces:**
- Produces:
  - `TranscriptFormat`（静态工具）：
    - `String toJson(String engineId, String language, long durationMs, List<Segment> segments)` → `{"segments":[{startMs,endMs,text,speaker?}],"engine","language","durationMs"}`（speaker 为 null 时省略该键）
    - `String toSrt(List<Segment> segments)` → 标准 SRT（序号从 1，`00:00:00,100 --> 00:00:01,660`，毫秒 3 位，段间空行）
    - `String toTxt(List<Segment> segments)` → 每段一行 `[mm:ss] text`
    - `static String timecode(long ms)` → `mm:ss`（≥1 小时 `h:mm:ss`）
  - `SegmentMerger`：`static List<Segment> merge(List<Segment> segments, long gapMs)` —— 按 startMs 排序；`next.startMs - cur.endMs < gapMs` 且非说话人冲突（仅两者非空且不等）→ 合并（start=较早，end=较晚，text 直接拼接，speaker 取有值方）

- [ ] **Step 1: 写测试（先失败）**

`engine/TranscriptFormatTest.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptFormatTest {

    private final List<Segment> segments = List.of(
            new Segment(0, 1660, "嘎嘎会，可以，这把稳了。", 1),
            new Segment(2000, 6100, "Let me check the question.", 2),
            new Segment(65000, 70000, "无说话人", null));

    @Test
    void jsonStructure() {
        String json = TranscriptFormat.toJson("minimax", "zh", 71000, segments);
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("engine").asText()).isEqualTo("minimax");
        assertThat(root.get("language").asText()).isEqualTo("zh");
        assertThat(root.get("durationMs").asLong()).isEqualTo(71000);
        assertThat(root.get("segments").size()).isEqualTo(3);
        assertThat(root.get("segments").get(0).get("speaker").asInt()).isEqualTo(1);
        assertThat(root.get("segments").get(2).get("speaker").isNull()).isTrue();
    }

    @Test
    void srtFormat() {
        String srt = TranscriptFormat.toSrt(segments.subList(0, 2));
        assertThat(srt).startsWith("1\n00:00:00,000 --> 00:00:01,660\n嘎嘎会，可以，这把稳了。\n\n")
                .contains("2\n00:00:02,000 --> 00:00:06,100\nLet me check the question.\n");
    }

    @Test
    void txtFormatUsesMinutePrefix() {
        String txt = TranscriptFormat.toTxt(segments);
        assertThat(txt).startsWith("[00:00] 嘎嘎会，可以，这把稳了。\n")
                .contains("[00:02] Let me check the question.\n")
                .contains("[01:05] 无说话人");
    }

    @Test
    void timecodeHours() {
        assertThat(TranscriptFormat.timecode(65_000)).isEqualTo("01:05");
        assertThat(TranscriptFormat.timecode(3_723_000)).isEqualTo("1:02:03");
    }
}
```

`engine/SegmentMergerTest.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentMergerTest {

    @Test
    void mergesGapBelowThreshold() {
        List<Segment> merged = SegmentMerger.merge(List.of(
                new Segment(0, 1000, "你好", 1),
                new Segment(1500, 2000, "呀。", 1),
                new Segment(6000, 7000, "下一句", 1)), 800);
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).startMs()).isEqualTo(0);
        assertThat(merged.get(0).endMs()).isEqualTo(2000);
        assertThat(merged.get(0).text()).isEqualTo("你好呀。");
        assertThat(merged.get(0).speaker()).isEqualTo(1);
    }

    @Test
    void speakerConflictPreventsMerge() {
        List<Segment> merged = SegmentMerger.merge(List.of(
                new Segment(0, 1000, "甲", 1),
                new Segment(1200, 2000, "乙", 2)), 800);
        assertThat(merged).hasSize(2);
    }

    @Test
    void nullSpeakerMergesFreely() {
        List<Segment> merged = SegmentMerger.merge(List.of(
                new Segment(0, 1000, "a", null),
                new Segment(1300, 2000, "b", 3)), 800);
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).speaker()).isEqualTo(3);
    }

    @Test
    void gapAtOrAboveThresholdNotMerged() {
        List<Segment> merged = SegmentMerger.merge(List.of(
                new Segment(0, 1000, "a", null),
                new Segment(1800, 2000, "b", null)), 800);
        assertThat(merged).hasSize(2);
    }
}
```

Run: `cd backend && ./mvnw -q test`
Expected: FAIL，编译错误

- [ ] **Step 2: 实现 TranscriptFormat**

`engine/TranscriptFormat.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class TranscriptFormat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TranscriptFormat() {}

    public static String toJson(String engineId, String language, long durationMs, List<Segment> segments) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("engine", engineId);
        root.put("language", language);
        root.put("durationMs", durationMs);
        ArrayNode arr = root.putArray("segments");
        for (Segment s : segments) {
            ObjectNode n = arr.addObject();
            n.put("startMs", s.startMs());
            n.put("endMs", s.endMs());
            n.put("text", s.text());
            if (s.speaker() != null) n.put("speaker", s.speaker());
        }
        return MAPPER.writeValueAsString(root);
    }

    public static String toSrt(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            sb.append(i + 1).append('\n')
                    .append(srtTime(s.startMs())).append(" --> ").append(srtTime(s.endMs())).append('\n')
                    .append(s.text()).append('\n').append('\n');
        }
        return sb.toString();
    }

    public static String toTxt(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            sb.append('[').append(timecode(s.startMs())).append("] ").append(s.text()).append('\n');
        }
        return sb.toString();
    }

    public static String timecode(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return "%d:%02d:%02d".formatted(h, m, s);
        return "%02d:%02d".formatted(m, s);
    }

    private static String srtTime(long ms) {
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        long s = (ms % 60_000) / 1000;
        long milli = ms % 1000;
        return "%02d:%02d:%02d,%03d".formatted(h, m, s, milli);
    }
}
```

- [ ] **Step 3: 实现 SegmentMerger**

`engine/SegmentMerger.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SegmentMerger {

    private SegmentMerger() {}

    public static List<Segment> merge(List<Segment> segments, long gapMs) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingLong(Segment::startMs));
        List<Segment> out = new ArrayList<>();
        for (Segment cur : sorted) {
            if (out.isEmpty()) {
                out.add(cur);
                continue;
            }
            Segment prev = out.get(out.size() - 1);
            boolean speakerConflict = cur.speaker() != null && prev.speaker() != null
                    && !cur.speaker().equals(prev.speaker());
            if (cur.startMs() - prev.endMs() < gapMs && !speakerConflict) {
                Integer speaker = prev.speaker() != null ? prev.speaker() : cur.speaker();
                out.set(out.size() - 1, new Segment(prev.startMs(), Math.max(prev.endMs(), cur.endMs()),
                        prev.text() + cur.text(), speaker));
            } else {
                out.add(cur);
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: 转写产物格式器与分段归并"
```

---

### Task 5: AppProperties 转写配置 + FfmpegService.detectSilences/cutAudio + AudioSegmenter

**Files:**
- Modify: `backend/src/main/java/com/waveminer/waveminer/config/AppProperties.java`、`media/FfmpegService.java`、`backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/waveminer/waveminer/media/AudioSegmenter.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/media/AudioSegmenterTest.java`（新建）；`FfmpegServiceTest` 追加

**Interfaces:**
- Consumes: `FfmpegService.Runner`（3 参含 cancelSignal）、`ProcessResult`
- Produces:
  - `AppProperties` 新增（含 getter/setter）：`minimaxBaseUrl="https://api.minimax.cn"`、`minimaxModel="asr-1.0"`、`aliyunBaseUrl="https://dashscope.aliyuncs.com/api/v1"`、`aliyunModel="qwen-audio-3.0-asr-flash-filetrans"`、`engineHttpTimeoutSeconds=600`（方法 `Duration engineHttpTimeout()`）、`engineRetryAttempts=3`、`transcribePollIntervalSeconds=5`、`transcribeTimeoutSeconds=7200`、`silenceThresholdDb=-35.0`（double）、`silenceMinDurationSec=0.6`（double）、`mergeGapMs=800`
  - `FfmpegService`：
    - `record Silence(double startSec, double endSec)`（定义在 FfmpegService 内部：`FfmpegService.Silence`）
    - `List<String> detectSilencesCommand(Path in)`：`{ffmpeg} -i {in} -af silencedetect=noise={threshold}dB:d={minDur} -f null -`
    - `List<Silence> detectSilences(Path in)`：先 `probeDurationSeconds`，再解析输出行 `silence_start: X` / `silence_end: Y | silence_duration: Z`；结尾未闭合的 silence_start 以音频时长收尾
    - `static double parseSilenceStart(String line)` / `static double parseSilenceEnd(String line)`（包级，供测试）
    - `List<String> cutAudioCommand(Path in, Path out, double startSec, double endSec)`：`{ffmpeg} -y -i {in} -ss {s} -to {e} -vn -ac 1 -ar 16000 -c:a aac {out}`
    - `void cutAudio(Path in, Path out, double startSec, double endSec, BooleanSupplier cancelSignal)`（非零退出 → ProcessFailedException 中文）
  - `AudioSegmenter`（@Component，构造参数 `FfmpegService`）：
    - `record Chunk(double startSec, double endSec, Path file)`
    - `List<Chunk> split(Path input, Path workDir, long maxDurationSec, BooleanSupplier cancelSignal)`：探测时长 → detectSilences → cutPoints → 逐片 `cutAudio` 到 `workDir/chunk-001.m4a`…；切分过程中失败则删除已生成片后原样抛出
    - `static List<double[]> cutPoints(double durationSec, List<FfmpegService.Silence> silences, double maxSec)`（包级，纯函数）：`durationSec <= maxSec` 返回空；否则贪心——窗口 `[pos, pos+maxSec]` 内取"静音中点 > pos+maxSec/2"的最大者，无则 `pos+maxSec`；产出区间列表，首区间 start=0，末区间 end=durationSec

- [ ] **Step 1: AppProperties/application.yml 扩展**

`AppProperties` 追加字段与 getter/setter（模式同现有）。`application.yml` 的 `app:` 下追加：

```yaml
  # —— 转写引擎（计划 2）——
  minimax-base-url: https://api.minimax.cn
  minimax-model: asr-1.0
  aliyun-base-url: https://dashscope.aliyuncs.com/api/v1
  aliyun-model: qwen-audio-3.0-asr-flash-filetrans
  engine-http-timeout-seconds: 600
  engine-retry-attempts: 3
  transcribe-poll-interval-seconds: 5
  transcribe-timeout-seconds: 7200
  silence-threshold-db: -35.0
  silence-min-duration-sec: 0.6
  merge-gap-ms: 800
```

- [ ] **Step 2: 写测试（先失败）**

`media/AudioSegmenterTest.java`：

```java
package com.waveminer.waveminer.media;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioSegmenterTest {

    private static FfmpegService.Silence sil(double s, double e) {
        return new FfmpegService.Silence(s, e);
    }

    @Test
    void shortAudioNeedsNoCut() {
        assertThat(AudioSegmenter.cutPoints(120, List.of(), 500)).isEmpty();
    }

    @Test
    void cutsAtSilenceMidpoints() {
        List<double[]> cuts = AudioSegmenter.cutPoints(900, List.of(sil(490, 494)), 500);
        assertThat(cuts).hasSize(2);
        assertThat(cuts.get(0)).containsExactly(0.0, 492.0);
        assertThat(cuts.get(1)).containsExactly(492.0, 900.0);
    }

    @Test
    void multipleCutsAtSilenceMidpoints() {
        List<double[]> cuts = AudioSegmenter.cutPoints(1200, List.of(sil(490, 494), sil(980, 984)), 500);
        assertThat(cuts).hasSize(3);
        assertThat(cuts.get(0)).containsExactly(0.0, 492.0);
        assertThat(cuts.get(1)).containsExactly(492.0, 982.0);
        assertThat(cuts.get(2)).containsExactly(982.0, 1200.0);
    }

    @Test
    void noSilenceForcesHardCut() {
        List<double[]> cuts = AudioSegmenter.cutPoints(1200, List.of(), 500);
        assertThat(cuts).hasSize(3);
        assertThat(cuts.get(0)).containsExactly(0.0, 500.0);
        assertThat(cuts.get(1)).containsExactly(500.0, 1000.0);
        assertThat(cuts.get(2)).containsExactly(1000.0, 1200.0);
    }

    @Test
    void silenceNearWindowEndPreferred() {
        List<double[]> cuts = AudioSegmenter.cutPoints(900, List.of(sil(298, 302), sil(478, 482)), 500);
        assertThat(cuts).hasSize(2);
        assertThat(cuts.get(0)).containsExactly(0.0, 480.0);
        assertThat(cuts.get(1)).containsExactly(480.0, 900.0);
    }

    @Test
    void parseSilenceLines() {
        assertThat(FfmpegService.parseSilenceStart("[silencedetect @ 0x0] silence_start: 12.3456")).isEqualTo(12.3456);
        assertThat(FfmpegService.parseSilenceEnd(
                "[silencedetect @ 0x0] silence_end: 13.5 | silence_duration: 1.154")).isEqualTo(13.5);
    }
}
```

`FfmpegServiceTest` 追加（沿用现有 lastCommand 桩模式）：

```java
    @Test
    void detectSilencesCommandShapeAndParsing() {
        FfmpegService svc = new FfmpegService((command, timeout, cancel) -> {
            lastCommand = command;
            if (command.get(0).equals("ffprobe")) {
                return new ProcessResult(0, "{\"format\":{\"duration\":\"30.0\"}}");
            }
            return new ProcessResult(0, """
                    [silencedetect @ 0x0] silence_start: 1.0
                    [silencedetect @ 0x0] silence_end: 2.0 | silence_duration: 1.0
                    [silencedetect @ 0x0] silence_start: 25.5
                    """);
        }, new AppProperties());
        List<FfmpegService.Silence> silences = svc.detectSilences(Path.of("/v/a.m4a"));
        assertThat(lastCommand).contains("-af", "silencedetect=noise=-35dB:d=0.6");
        assertThat(silences).hasSize(2);
        assertThat(silences.get(0).startSec()).isEqualTo(1.0);
        assertThat(silences.get(0).endSec()).isEqualTo(2.0);
        // 未闭合静音以音频时长收尾
        assertThat(silences.get(1).startSec()).isEqualTo(25.5);
        assertThat(silences.get(1).endSec()).isEqualTo(30.0);
    }
```

Run: `cd backend && ./mvnw -q test`
Expected: FAIL，编译错误

- [ ] **Step 3: 实现 FfmpegService 扩展**

`FfmpegService` 追加（内部 record Silence 放类顶部区域）：

```java
    public record Silence(double startSec, double endSec) {}

    List<String> detectSilencesCommand(Path in) {
        return List.of(props.getFfmpegPath(), "-i", in.toString(),
                "-af", "silencedetect=noise=" + props.getSilenceThresholdDb() + "dB:d="
                        + props.getSilenceMinDurationSec(),
                "-f", "null", "-");
    }

    static double parseSilenceStart(String line) {
        int idx = line.indexOf("silence_start:");
        if (idx < 0) throw new IllegalArgumentException("非 silencedetect 输出行: " + line);
        return Double.parseDouble(line.substring(idx + "silence_start:".length()).trim());
    }

    static double parseSilenceEnd(String line) {
        int idx = line.indexOf("silence_end:");
        if (idx < 0) throw new IllegalArgumentException("非 silencedetect 输出行: " + line);
        return Double.parseDouble(line.substring(idx + "silence_end:".length()).trim());
    }

    public List<Silence> detectSilences(Path in) {
        double duration = probeDurationSeconds(in);
        ProcessResult r = runner.run(detectSilencesCommand(in), props.processTimeout());
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("ffmpeg 静音检测失败:\n" + tail(r.output()));
        }
        List<Silence> out = new ArrayList<>();
        Double openStart = null;
        for (String line : r.output().split("\n")) {
            if (line.contains("silence_start:")) {
                openStart = parseSilenceStart(line);
            } else if (line.contains("silence_end:") && openStart != null) {
                out.add(new Silence(openStart, parseSilenceEnd(line)));
                openStart = null;
            }
        }
        if (openStart != null) {
            out.add(new Silence(openStart, duration));
        }
        return out;
    }

    List<String> cutAudioCommand(Path in, Path out, double startSec, double endSec) {
        return List.of(props.getFfmpegPath(), "-y", "-i", in.toString(),
                "-ss", trimNum(startSec), "-to", trimNum(endSec),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "aac", out.toString());
    }

    public void cutAudio(Path in, Path out, double startSec, double endSec, BooleanSupplier cancelSignal) {
        ProcessResult r = runner.run(cutAudioCommand(in, out, startSec, endSec), props.processTimeout(), cancelSignal);
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("ffmpeg 切分音频失败:\n" + tail(r.output()));
        }
    }

    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
```

（import 追加 `java.util.ArrayList`、`java.util.function.BooleanSupplier`——`List` 已有。）

- [ ] **Step 4: 实现 AudioSegmenter**

`media/AudioSegmenter.java`：

```java
package com.waveminer.waveminer.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class AudioSegmenter {

    public record Chunk(double startSec, double endSec, Path file) {}

    private final FfmpegService ffmpeg;

    public AudioSegmenter(FfmpegService ffmpeg) { this.ffmpeg = ffmpeg; }

    public List<Chunk> split(Path input, Path workDir, long maxDurationSec, BooleanSupplier cancelSignal) {
        double duration = ffmpeg.probeDurationSeconds(input);
        List<double[]> ranges = cutPoints(duration, ffmpeg.detectSilences(input), maxDurationSec);
        List<Chunk> chunks = new ArrayList<>();
        try {
            int i = 1;
            for (double[] range : ranges) {
                Path out = workDir.resolve(String.format("chunk-%03d.m4a", i++));
                ffmpeg.cutAudio(input, out, range[0], range[1], cancelSignal);
                chunks.add(new Chunk(range[0], range[1], out));
            }
        } catch (RuntimeException e) {
            chunks.forEach(c -> {
                try {
                    Files.deleteIfExists(c.file());
                } catch (Exception ignored) {
                    // 清理失败不影响主流程
                }
            });
            throw e;
        }
        return chunks;
    }

    /**
     * 纯函数：返回切分区间 [start,end] 列表；无需切分返回空列表。
     * 贪心：窗口 [pos, pos+maxSec] 内取"静音中点 > pos+maxSec/2"的最大者；无则 pos+maxSec。
     */
    static List<double[]> cutPoints(double durationSec, List<FfmpegService.Silence> silences, double maxSec) {
        if (durationSec <= maxSec) return List.of();
        List<double[]> ranges = new ArrayList<>();
        double start = 0;
        while (durationSec - start > maxSec) {
            double windowEnd = start + maxSec;
            double best = -1;
            for (FfmpegService.Silence s : silences) {
                double mid = (s.startSec() + s.endSec()) / 2;
                if (mid > start + maxSec / 2 && mid <= windowEnd && mid > best) {
                    best = mid;
                }
            }
            double cut = best > 0 ? best : windowEnd;
            ranges.add(new double[]{start, cut});
            start = cut;
        }
        ranges.add(new double[]{start, durationSec});
        return ranges;
    }
}
```

- [ ] **Step 5: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend && git commit -m "feat: 静音检测与超长音频切分器"
```

---

### Task 6: EngineHttpClient（超时/重试）+ MultipartBuilder + MiniMax 引擎

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/engine/EngineHttpClient.java`、`engine/MultipartBuilder.java`、`engine/MinimaxAsrEngine.java`、`config/EngineConfig.java`
- Modify: `backend/pom.xml`（wiremock test 依赖）
- Test: `backend/src/test/java/com/waveminer/waveminer/engine/MinimaxAsrEngineTest.java`

**Interfaces:**
- Consumes: Task 2 全部类型、`AppProperties`、`EngineKeyProvider`
- Produces:
  - `EngineHttpClient`（@Component）：`record EngineHttpResponse(int statusCode, String body)`；`EngineHttpResponse send(java.net.http.HttpRequest request)` —— 请求超时 `app.engine-http-timeout()`；429/5xx/IOException 重试 `app.engine-retry-attempts` 次（失败第 n 次后睡 `backoffBaseMillis * n`），耗尽抛最后捕获的 `EngineException`；包级字段 `long backoffBaseMillis = 1000`（测试置 1）
  - `MultipartBuilder`：`add(String name, String value)`、`addFile(String fileFieldName, String filename, String contentType, byte[] data)`、`String contentType()`（含 boundary）、`byte[] build()`；build 输出 `--{boundary}\r\nContent-Disposition: form-data; name="..."; filename="..."\r\nContent-Type: ...\r\n\r\n<bytes>\r\n`（文件段）/普通段无 filename/ContentType；结尾 `--{boundary}--\r\n`
  - `MinimaxAsrEngine implements TranscriptionEngine`：
    - `id()="minimax"`；`info()=EngineInfo("minimax","MiniMax 语音识别",500,50L*1024*1024,true,LANGS)`，`LANGS=[zh,yue,en,ja,ko,th,vi,id,ms,fil,ar,tr,fr,de,es,it,pt,pl,ru,uk]`
    - `transcribe`：无 key → `EngineException("未配置 MiniMax API Key，请先在设置页填写")`；文件 >50MB → `EngineException("音频文件超过 MiniMax 50MB 大小限制（当前 nMB），请先压缩或切片")`；multipart 字段 `model={app.minimax-model}`、`response_format=verbose_json`、`timestamp_level=sentence`、`file`；language≠auto 时加 `language` 请求头；`POST {app.minimax-base-url}/v1/speech_to_text`
    - 非 200 → `EngineException("MiniMax 转写失败（HTTP {code}）: {error.message}。原始响应已存档到 engine_raw/", rawBody)`
    - 200 → `List<Segment> parseVerboseJson(String json)`（start/end 秒 ×1000 四舍五入；speaker "S1"→1 去 `[^0-9]`，空→null）→ `TranscribeResult(segments, body)`
    - `static Integer speakerOf(String speaker)`（包级）
  - `config/EngineConfig`：`@Bean MinimaxAsrEngine minimaxAsrEngine(EngineHttpClient http, EngineKeyProvider keys, AppProperties props)`

- [ ] **Step 1: pom.xml 增加 WireMock（test）**

`backend/pom.xml` `<dependencies>` 追加：

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.13.1</version>
    <scope>test</scope>
</dependency>
```

（若 `3.13.1` 拉取失败，改用 `3.9.2`。）

- [ ] **Step 2: 写测试（先失败）**

`engine/MinimaxAsrEngineTest.java`：

```java
package com.waveminer.waveminer.engine;

import com.waveminer.waveminer.config.AppProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinimaxAsrEngineTest {

    @TempDir
    Path tmp;

    private WireMockServer wire;
    private EngineHttpClient http;
    private AppProperties props;

    /** 官方文档 verbose_json 响应样例 */
    private static final String VERBOSE_JSON = """
            {
              "text": "嘎嘎会，可以，这把稳了。来检查一下，读下题。",
              "duration": 12.744,
              "n_speakers": 2,
              "segments": [
                {"id": 0, "start": 0.1, "end": 1.66, "speaker": "S1", "text": "嘎嘎会，可以，这把稳了。"},
                {"id": 1, "start": 2, "end": 6.1, "speaker": "S2", "text": "来检查一下，读下题。"}
              ],
              "trace_id": "021785229015510a2c883cf675b9804d"
            }""";

    @BeforeEach
    void setUp() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        props = new AppProperties();
        props.setMinimaxBaseUrl(wire.baseUrl());
        http = new EngineHttpClient(props);
        http.backoffBaseMillis = 1;
    }

    @AfterEach
    void tearDown() {
        wire.stop();
    }

    private MinimaxAsrEngine engine(String key) {
        EngineKeyProvider keys = mock(EngineKeyProvider.class);
        when(keys.getKey("minimax")).thenReturn(key);
        return new MinimaxAsrEngine(http, keys, props);
    }

    private Path audioFile(String name) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "fake-audio");
        return f;
    }

    @Test
    void transcribesVerboseJsonToSegments() throws Exception {
        wire.stubFor(post(urlEqualTo("/v1/speech_to_text"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(VERBOSE_JSON)));

        TranscribeResult r = engine("sk-test")
                .transcribe(audioFile("a.mp3"), TranscribeOptions.of("zh", false), ProgressCallback.noop());

        assertThat(r.segments()).containsExactly(
                new Segment(100, 1660, "嘎嘎会，可以，这把稳了。", 1),
                new Segment(2000, 6100, "来检查一下，读下题。", 2));
        assertThat(r.rawResponse()).contains("trace_id");
        var events = wire.getAllServeEvents();
        assertThat(events).hasSize(1);
        var req = events.get(0).getRequest();
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(req.getHeader("language")).isEqualTo("zh");
        String body = new String(req.getBody());
        assertThat(body).contains("name=\"model\"", "asr-1.0")
                .contains("name=\"response_format\"", "verbose_json")
                .contains("name=\"timestamp_level\"", "sentence");
    }

    @Test
    void autoLanguageOmitsHeader() throws Exception {
        wire.stubFor(post(urlEqualTo("/v1/speech_to_text"))
                .willReturn(aResponse().withBody(VERBOSE_JSON)));
        engine("sk-test").transcribe(audioFile("a.mp3"), TranscribeOptions.of("auto", false), ProgressCallback.noop());
        assertThat(wire.getAllServeEvents().get(0).getRequest().getHeader("language")).isNull();
    }

    @Test
    void missingKeyFailsEarly() throws Exception {
        assertThatThrownBy(() -> engine(null).transcribe(audioFile("a.mp3"),
                        TranscribeOptions.of("auto", false), ProgressCallback.noop()))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("未配置 MiniMax API Key");
    }

    @Test
    void apiErrorSurfacesChineseMessageWithRaw() throws Exception {
        wire.stubFor(post(urlEqualTo("/v1/speech_to_text"))
                .willReturn(aResponse().withStatus(400).withBody("""
                        {"type":"error","error":{"type":"bad_request_error",
                        "message":"invalid params, audio duration 623.4s exceeds the limit of 500s (2013)",
                        "http_code":"400"},"request_id":"req-1"}""")));
        assertThatThrownBy(() -> engine("sk-test").transcribe(audioFile("a.mp3"),
                        TranscribeOptions.of("auto", false), ProgressCallback.noop()))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("500s")
                .hasMessageContaining("MiniMax")
                .hasMessageContaining("engine_raw")
                .extracting(e -> ((EngineException) e).getRawResponse())
                .asString().contains("request_id");
    }

    @Test
    void rateLimitRetriesThenSucceeds() throws Exception {
        wire.stubFor(post(urlEqualTo("/v1/speech_to_text"))
                .willReturn(aResponse().withStatus(429)
                        .withBody("{\"type\":\"error\",\"error\":{\"message\":\"rate limit\"}}")));
        wire.stubFor(post(urlEqualTo("/v1/speech_to_text"))
                .willReturn(aResponse().withBody(VERBOSE_JSON)));

        TranscribeResult r = engine("sk-test").transcribe(audioFile("a.mp3"),
                TranscribeOptions.of("auto", false), ProgressCallback.noop());
        assertThat(r.segments()).hasSize(2);
        assertThat(wire.getAllServeEvents()).hasSize(2);
    }

    @Test
    void parseSpeaker() {
        assertThat(MinimaxAsrEngine.speakerOf("S1")).isEqualTo(1);
        assertThat(MinimaxAsrEngine.speakerOf("S12")).isEqualTo(12);
        assertThat(MinimaxAsrEngine.speakerOf(null)).isNull();
        assertThat(MinimaxAsrEngine.speakerOf("")).isNull();
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=MinimaxAsrEngineTest`
Expected: FAIL，编译错误

- [ ] **Step 3: 实现 MultipartBuilder + EngineHttpClient**

`engine/MultipartBuilder.java`：

```java
package com.waveminer.waveminer.engine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MultipartBuilder {

    private final String boundary = "waveminer-" + UUID.randomUUID();
    private final Map<String, String> fields = new LinkedHashMap<>();
    private String fileFieldName;
    private String fileName;
    private String fileContentType;
    private byte[] fileData;

    public MultipartBuilder add(String name, String value) {
        fields.put(name, value);
        return this;
    }

    public MultipartBuilder addFile(String fileFieldName, String filename, String contentType, byte[] data) {
        this.fileFieldName = fileFieldName;
        this.fileName = filename;
        this.fileContentType = contentType;
        this.fileData = data;
        return this;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public byte[] build() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            if (fileFieldName != null) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + fileFieldName
                        + "\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: " + fileContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(fileData);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new EngineException("构造上传请求失败: " + e.getMessage(), e, null);
        }
    }
}
```

`engine/EngineHttpClient.java`：

```java
package com.waveminer.waveminer.engine;

import com.waveminer.waveminer.config.AppProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class EngineHttpClient {

    public record EngineHttpResponse(int statusCode, String body) {}

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final AppProperties props;
    long backoffBaseMillis = 1000;

    public EngineHttpClient(AppProperties props) { this.props = props; }

    public EngineHttpResponse send(HttpRequest request) {
        int attempts = Math.max(1, props.getEngineRetryAttempts());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code != 429 && code < 500) {
                    return new EngineHttpResponse(code, resp.body());
                }
                last = new EngineException("转写服务返回 HTTP " + code + ": " + snippet(resp.body()));
            } catch (IOException e) {
                last = new EngineException("请求转写服务失败: " + e.getMessage(), e, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("请求转写服务被中断", e, null);
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(backoffBaseMillis * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new EngineException("重试等待被中断", ie, null);
                }
            }
        }
        throw last;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
```

- [ ] **Step 4: 实现 MinimaxAsrEngine + EngineConfig**

`engine/MinimaxAsrEngine.java`：

```java
package com.waveminer.waveminer.engine;

import com.waveminer.waveminer.config.AppProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class MinimaxAsrEngine implements TranscriptionEngine {

    static final List<String> LANGS = List.of("zh", "yue", "en", "ja", "ko", "th", "vi", "id",
            "ms", "fil", "ar", "tr", "fr", "de", "es", "it", "pt", "pl", "ru", "uk");

    private final EngineHttpClient http;
    private final EngineKeyProvider keys;
    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public MinimaxAsrEngine(EngineHttpClient http, EngineKeyProvider keys, AppProperties props) {
        this.http = http;
        this.keys = keys;
        this.props = props;
    }

    @Override
    public String id() { return "minimax"; }

    @Override
    public EngineInfo info() {
        return new EngineInfo("minimax", "MiniMax 语音识别", 500, 50L * 1024 * 1024, true, LANGS);
    }

    @Override
    public TranscribeResult transcribe(Path mediaFile, TranscribeOptions options, ProgressCallback progress) {
        String key = keys.getKey(id());
        if (key == null || key.isBlank()) {
            throw new EngineException("未配置 MiniMax API Key，请先在设置页填写");
        }
        long size;
        try {
            size = Files.size(mediaFile);
        } catch (IOException e) {
            throw new EngineException("读取音频文件失败: " + e.getMessage(), e, null);
        }
        if (size > info().maxSizeBytes()) {
            throw new EngineException("音频文件超过 MiniMax 50MB 大小限制（当前 "
                    + size / 1024 / 1024 + "MB），请先压缩或切片");
        }

        String filename = mediaFile.getFileName().toString();
        byte[] data;
        try {
            data = Files.readAllBytes(mediaFile);
        } catch (IOException e) {
            throw new EngineException("读取音频文件失败: " + e.getMessage(), e, null);
        }
        MultipartBuilder mp = new MultipartBuilder()
                .add("model", props.getMinimaxModel())
                .add("response_format", "verbose_json")
                .add("timestamp_level", "sentence")
                .addFile("file", filename, contentTypeOf(filename), data);

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(
                        props.getMinimaxBaseUrl() + "/v1/speech_to_text"))
                .timeout(props.engineHttpTimeout())
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", mp.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(mp.build()));
        if (!"auto".equals(options.language()) && options.language() != null && !options.language().isBlank()) {
            b.header("language", options.language());
        }

        EngineHttpClient.EngineHttpResponse resp = http.send(b.build());
        if (resp.statusCode() != 200) {
            throw new EngineException("MiniMax 转写失败（HTTP " + resp.statusCode() + "）: "
                    + errorMessage(resp.body()) + "。原始响应已存档到 engine_raw/", resp.body());
        }
        return new TranscribeResult(parseVerboseJson(resp.body()), resp.body());
    }

    List<Segment> parseVerboseJson(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode segments = root.path("segments");
            if (segments.isMissingNode() || !segments.isArray()) {
                throw new EngineException("MiniMax 响应缺少 segments 字段", json);
            }
            List<Segment> out = new ArrayList<>();
            for (JsonNode s : segments) {
                out.add(new Segment(
                        Math.round(s.path("start").asDouble() * 1000),
                        Math.round(s.path("end").asDouble() * 1000),
                        s.path("text").asText(""),
                        speakerOf(s.path("speaker").asText(null))));
            }
            return out;
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("MiniMax 响应解析失败: " + e.getMessage(), e, json);
        }
    }

    static Integer speakerOf(String speaker) {
        if (speaker == null || speaker.isBlank()) return null;
        String digits = speaker.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : Integer.parseInt(digits);
    }

    private String errorMessage(String body) {
        try {
            String msg = mapper.readTree(body).path("error").path("message").asText("");
            return msg.isBlank() ? String.valueOf(body) : msg;
        } catch (Exception e) {
            return body == null ? "" : body;
        }
    }

    private static String contentTypeOf(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "aac" -> "audio/aac";
            case "opus" -> "audio/opus";
            case "ogg" -> "audio/ogg";
            case "aiff" -> "audio/aiff";
            default -> "application/octet-stream";
        };
    }
}
```

`config/EngineConfig.java`：

```java
package com.waveminer.waveminer.config;

import com.waveminer.waveminer.engine.EngineHttpClient;
import com.waveminer.waveminer.engine.EngineKeyProvider;
import com.waveminer.waveminer.engine.MinimaxAsrEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {

    @Bean
    public MinimaxAsrEngine minimaxAsrEngine(EngineHttpClient http, EngineKeyProvider keys, AppProperties props) {
        return new MinimaxAsrEngine(http, keys, props);
    }
}
```

- [ ] **Step 5: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend && git commit -m "feat: 引擎 HTTP 客户端与 MiniMax 转写引擎"
```

---

### Task 7: TaskEventPublisher + TaskRunner 事件埋点 + TranscribeStage + 产物白名单/MIME

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/pipeline/TaskEventPublisher.java`、`stage/TranscribeStage.java`
- Modify: `pipeline/TaskRunner.java`、`store/ArtifactStore.java`、`api/TaskController.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/stage/TranscribeStageTest.java`；`store/ArtifactStoreTest` 追加；`TaskRunnerTest` 构造器更新

**Interfaces:**
- Consumes: Task 2-6 全部、`TaskContext.engineId()/language()/diarize()`（Task 3）、`TaskCancelledException`（pipeline 包，无参构造）
- Produces:
  - `pipeline/TaskEventPublisher`（@Component）：
    - `record TaskEvent(String taskId, String type, String stage, String status, String message, Instant at)` + 静态工厂 `stage(taskId, stage, status, message)`（type="stage"）、`progress(taskId, stage, message)`（type="progress"）、`task(taskId, status)`（type="task"）
    - `interface Listener { void onEvent(TaskEvent e); }`；`addListener`/`removeListener`/`publish`（逐个通知，监听器异常吞掉记 warn 日志）
  - `TaskRunner` 构造器追加第 5 参数 `TaskEventPublisher events`：stage `RUNNING`/`DONE`/`FAILED`（含取消导致的 FAILED）后发布 `stage` 事件；任务终态（COMPLETED/FAILED/CANCELLED）发布 `task` 事件。`TaskRunnerTest` 更新构造并新增事件断言用例
  - `stage/TranscribeStage implements StageHandler`（@Component，type=TRANSCRIBE），构造 `(EngineRegistry registry, FfmpegService ffmpeg, AudioSegmenter segmenter, TaskEventPublisher events, AppProperties props)`：
    - 输入解析：`ctx.artifactOf(AUDIO_EXTRACT)` → `ctx.artifactOf(DOWNLOAD)` → `ctx.uploadedFile()`；皆无 → `IllegalStateException("转写缺少音频/视频输入（需要上游产物或上传文件）")`
    - 引擎：`registry.get(ctx.engineId())`；随后取消检查（`ctx.cancelRequested()` → `TaskCancelledException`）
    - 视频输入（`MediaTypes.isVideo`）→ `ffmpeg.extractAudio(input, dir/audio.m4a, ctx::cancelRequested)` 后以 `audio.m4a` 为输入并重探时长
    - 时长 > 引擎 `maxDurationSec` → 分片路径：`segmenter.split(input, dir/"chunks", maxDur, ctx::cancelRequested)`；逐片（进度 `转写分片 i/n`；每片前查取消 → TaskCancelledException）`engine.transcribe(chunk.file(), options, callback(ctx))`；raw 存 `engine_raw/{engineId}-chunk-XXX.json`；时间戳偏移 `chunk.startSec()*1000`；全部成功后 `SegmentMerger.merge(all, props.getMergeGapMs())` 并递归删除 `chunks/`；失败保留 chunks 目录
    - 时长 ≤ 上限 → 单次转写（进度 `正在调用转写引擎…`），raw 存 `engine_raw/{engineId}.json`
    - 引擎抛 `EngineCancelledException` → 转抛 `TaskCancelledException`；其他 `EngineException`：有 rawResponse 时先存档 `engine_raw/{engineId}.json` 再原样抛出
    - 成功：写 `transcript.json`（engine/engineId、language=ctx.language()、durationMs）/`transcript.srt`/`transcript.txt` → `ctx.putArtifact(TRANSCRIBE, "transcript.json")`；进度 `转写完成：N 段`
    - `private ProgressCallback callback(TaskContext ctx)`：update → `events.publish(progress(...))`；cancelled → `ctx.cancelRequested()`
  - `ArtifactStore.isAllowedArtifactName` 追加：精确 `transcript.json/transcript.srt/transcript.txt`；`engine_raw/<单层文件名>`（`rest.matches("[A-Za-z0-9._-]+")` 且不含 `..`）；`listArtifacts` 改 `Files.walk(dir, 2)` 并 `dir.relativize(p).toString().replace('\\','/')` 过滤（engine_raw 内文件以 `engine_raw/xxx.json` 形式列出）
  - `TaskController`：产物路由改 `@GetMapping("/{id}/artifacts/{name:.+}")`；MIME 表追加 `json→application/json`、`srt→application/x-subrip`、`txt→text/plain;charset=UTF-8`（`dir.resolve(name).normalize()` 与 `startsWith(dir)` 校验保留）

- [ ] **Step 1: ArtifactStore 测试追加（先失败）**

`store/ArtifactStoreTest` 追加：

```java
    @Test
    void transcriptAndEngineRawAllowed() throws Exception {
        ArtifactStore s = store();
        Path dir = s.taskDir("abc");
        Files.createDirectories(dir.resolve("engine_raw"));
        Files.writeString(dir.resolve("transcript.json"), "{}");
        Files.writeString(dir.resolve("transcript.srt"), "");
        Files.writeString(dir.resolve("transcript.txt"), "");
        Files.writeString(dir.resolve("engine_raw").resolve("minimax.json"), "{}");
        Files.writeString(dir.resolve("engine_raw").resolve("bad name!.json"), "{}");

        assertThat(s.isAllowedArtifactName("transcript.json")).isTrue();
        assertThat(s.isAllowedArtifactName("transcript.srt")).isTrue();
        assertThat(s.isAllowedArtifactName("transcript.txt")).isTrue();
        assertThat(s.isAllowedArtifactName("engine_raw/minimax.json")).isTrue();
        assertThat(s.isAllowedArtifactName("engine_raw/bad name!.json")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw/../../etc")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw/")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw")).isFalse();

        List<ArtifactInfo> artifacts = s.listArtifacts("abc");
        assertThat(artifacts).extracting(ArtifactInfo::name).contains(
                "transcript.json", "transcript.srt", "transcript.txt", "engine_raw/minimax.json");
    }
```

Run: `cd backend && ./mvnw -q test -Dtest=ArtifactStoreTest`
Expected: FAIL

- [ ] **Step 2: 写 TranscribeStage 测试（先失败）**

`stage/TranscribeStageTest.java`：

```java
package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.engine.EngineException;
import com.waveminer.waveminer.engine.EngineInfo;
import com.waveminer.waveminer.engine.EngineRegistry;
import com.waveminer.waveminer.engine.Segment;
import com.waveminer.waveminer.engine.TranscribeOptions;
import com.waveminer.waveminer.engine.TranscribeResult;
import com.waveminer.waveminer.engine.TranscriptionEngine;
import com.waveminer.waveminer.media.AudioSegmenter;
import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskCancelledException;
import com.waveminer.waveminer.pipeline.TaskContext;
import com.waveminer.waveminer.pipeline.TaskEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranscribeStageTest {

    @TempDir
    Path tmp;

    private FfmpegService ffmpeg;
    private AudioSegmenter segmenter;
    private TranscriptionEngine engine;
    private TranscribeStage stage;
    private TaskEventPublisher publisher;
    private final List<TaskEventPublisher.TaskEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setDataDir(tmp.toString());
        ffmpeg = mock(FfmpegService.class);
        segmenter = mock(AudioSegmenter.class);
        publisher = new TaskEventPublisher();
        publisher.addListener(events::add);
        engine = mock(TranscriptionEngine.class);
        when(engine.id()).thenReturn("fake");
        when(engine.info()).thenReturn(new EngineInfo("fake", "假引擎", 500, 50 * 1024 * 1024, true, List.of("zh")));

        @SuppressWarnings("unchecked")
        ObjectProvider<TranscriptionEngine> provider = mock(ObjectProvider.class);
        Mockito.when(provider.orderedStream()).thenReturn(java.util.stream.Stream.of(engine));
        EngineRegistry registry = new EngineRegistry(provider);

        stage = new TranscribeStage(registry, ffmpeg, segmenter, publisher, props);
    }

    private TaskContext ctx(String id, String url, Path uploaded) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(id));
        return new TaskContext(id, dir, url, uploaded, new AtomicBoolean(false), "fake", "zh", false);
    }

    private Path audio(String task, String name) throws Exception {
        Path p = tmp.resolve(task).resolve(name);
        Files.writeString(p, "audio");
        return p;
    }

    @Test
    void transcribesAudioInputAndWritesArtifacts() throws Exception {
        TaskContext c = ctx("t1", null, null);
        Path in = audio("t1", "audio.m4a");
        c.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(12.744);
        when(engine.transcribe(eq(in), eq(new TranscribeOptions("zh", false)), any()))
                .thenReturn(new TranscribeResult(
                        List.of(new Segment(100, 1660, "你好", 1)), "{\"segments\":[]}"));

        stage.run(c);

        assertThat(c.artifactOf(StageType.TRANSCRIBE)).hasFileName("transcript.json");
        Path dir = c.dir();
        assertThat(Files.readString(dir.resolve("transcript.json"))).contains("\"engine\":\"fake\"");
        assertThat(Files.readString(dir.resolve("transcript.srt")))
                .contains("00:00:00,100 --> 00:00:01,660");
        assertThat(Files.readString(dir.resolve("transcript.txt"))).contains("[00:00] 你好");
        assertThat(Files.readString(dir.resolve("engine_raw").resolve("fake.json"))).contains("segments");
        assertThat(events).anySatisfy(e -> assertThat(e.message()).contains("转写完成"));
    }

    @Test
    void videoInputExtractsAudioFirst() throws Exception {
        TaskContext c = ctx("t2", null, null);
        Path video = audio("t2", "source.mp4");
        c.putArtifact(StageType.DOWNLOAD, "source.mp4");
        Path outAudio = c.dir().resolve("audio.m4a");
        Mockito.doAnswer(inv -> {
            Files.writeString(outAudio, "audio");
            return null;
        }).when(ffmpeg).extractAudio(eq(video), eq(outAudio), any());
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(5.0);
        when(engine.transcribe(eq(outAudio), any(), any()))
                .thenReturn(new TranscribeResult(List.of(new Segment(0, 500, "hi", null)), "{}"));

        stage.run(c);

        Mockito.verify(ffmpeg).extractAudio(eq(video), eq(outAudio), any());
        assertThat(Files.exists(c.dir().resolve("transcript.json"))).isTrue();
    }

    @Test
    void longAudioSplitsOffsetsAndMerges() throws Exception {
        TaskContext c = ctx("t3", null, null);
        Path in = audio("t3", "audio.m4a");
        c.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(1200.0);
        Path chunk1 = c.dir().resolve("chunks").resolve("chunk-001.m4a");
        Path chunk2 = c.dir().resolve("chunks").resolve("chunk-002.m4a");
        Files.createDirectories(chunk1.getParent());
        Files.writeString(chunk1, "c1");
        Files.writeString(chunk2, "c2");
        when(segmenter.split(eq(in), any(Path.class), eq(500L), any())).thenReturn(List.of(
                new AudioSegmenter.Chunk(0, 492, chunk1),
                new AudioSegmenter.Chunk(492, 1200, chunk2)));
        when(engine.transcribe(eq(chunk1), any(), any())).thenReturn(new TranscribeResult(
                List.of(new Segment(0, 1000, "前半。", 1)), "{\"c\":1}"));
        when(engine.transcribe(eq(chunk2), any(), any())).thenReturn(new TranscribeResult(
                List.of(new Segment(0, 800, "后半。", 1)), "{\"c\":2}"));

        stage.run(c);

        String json = Files.readString(c.dir().resolve("transcript.json"));
        assertThat(json).contains("\"startMs\":492000"); // 第二片整体偏移 492s
        assertThat(Files.readString(c.dir().resolve("engine_raw").resolve("fake-chunk-001.json"))).contains("\"c\":1");
        assertThat(Files.exists(c.dir().resolve("chunks"))).isFalse(); // 成功后清理
    }

    @Test
    void engineFailureArchivesRawAndPropagates() throws Exception {
        TaskContext c = ctx("t4", null, null);
        Path in = audio("t4", "audio.m4a");
        c.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(10.0);
        when(engine.transcribe(eq(in), any(), any()))
                .thenThrow(new EngineException("引擎炸了", "{\"error\":1}"));

        assertThatThrownBy(() -> stage.run(c))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("引擎炸了");
        assertThat(Files.readString(c.dir().resolve("engine_raw").resolve("fake.json"))).contains("\"error\":1");
    }

    @Test
    void engineCancellationBecomesTaskCancelled() throws Exception {
        TaskContext c = ctx("t5", null, null);
        Path in = audio("t5", "audio.m4a");
        c.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(10.0);
        when(engine.transcribe(eq(in), any(), any()))
                .thenThrow(new com.waveminer.waveminer.engine.EngineCancelledException("已取消"));

        assertThatThrownBy(() -> stage.run(c)).isInstanceOf(TaskCancelledException.class);
    }

    @Test
    void unknownEngineFails() throws Exception {
        TaskContext c = new TaskContext("t6", Files.createDirectories(tmp.resolve("t6")),
                null, null, new AtomicBoolean(false), "no-such", "auto", false);
        assertThatThrownBy(() -> stage.run(c))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("未知转写引擎");
    }

    @Test
    void missingInputFails() throws Exception {
        TaskContext c = ctx("t7", "https://e.com/a", null);
        assertThatThrownBy(() -> stage.run(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("输入");
    }

    @Test
    void cancelRequestedStopsBeforeEngine() throws Exception {
        Path uploaded = audio("t8", "source.mp3");
        TaskContext c = new TaskContext("t8", Files.createDirectories(tmp.resolve("t8")),
                null, uploaded, new AtomicBoolean(true), "fake", "auto", false);
        assertThatThrownBy(() -> stage.run(c)).isInstanceOf(TaskCancelledException.class);
        Mockito.verify(engine, Mockito.never()).transcribe(any(), any(), any());
    }
}
```

- [ ] **Step 3: 实现 TaskEventPublisher**

`pipeline/TaskEventPublisher.java`：

```java
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
```

- [ ] **Step 4: TaskRunner 埋点 + 构造器/测试更新**

`TaskRunner` 构造器改为 `(AppProperties props, List<StageHandler> handlers, TaskRepository repo, ArtifactStore store, TaskEventPublisher events)`。发布点：

```java
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
```

`fail(...)` 中（updateStage 之后）补 `events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "FAILED", message))`；任务终态 `updateTaskStatus` 三处（COMPLETED / fail 内的 FAILED / cancel 路径的 CANCELLED，含 execute 开头的取消短路）各补一条 `events.publish(TaskEventPublisher.TaskEvent.task(taskId, "<终态名>"))`。

`TaskRunnerTest`：setUp 中创建 `TaskEventPublisher publisher = new TaskEventPublisher();` 并传入构造器；既有 4 个用例不动；追加：

```java
    @Test
    void publishesStageAndTaskEvents() {
        java.util.List<TaskEventPublisher.TaskEvent> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        publisher.addListener(seen::add);
        insertTask("ev");
        runner.submit(repo.find("ev").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                seen.stream().anyMatch(e -> e.type().equals("task")));
        assertThat(seen).anySatisfy(e -> assertThat(e.type()).isEqualTo("stage"));
        assertThat(seen).anySatisfy(e -> assertThat(e.status()).isEqualTo("COMPLETED"));
    }
```

- [ ] **Step 5: 实现 TranscribeStage**

`stage/TranscribeStage.java`：

```java
package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.engine.EngineCancelledException;
import com.waveminer.waveminer.engine.EngineException;
import com.waveminer.waveminer.engine.EngineRegistry;
import com.waveminer.waveminer.engine.ProgressCallback;
import com.waveminer.waveminer.engine.Segment;
import com.waveminer.waveminer.engine.SegmentMerger;
import com.waveminer.waveminer.engine.TranscribeOptions;
import com.waveminer.waveminer.engine.TranscribeResult;
import com.waveminer.waveminer.engine.TranscriptFormat;
import com.waveminer.waveminer.engine.TranscriptionEngine;
import com.waveminer.waveminer.media.AudioSegmenter;
import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.media.MediaTypes;
import com.waveminer.waveminer.pipeline.StageHandler;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskCancelledException;
import com.waveminer.waveminer.pipeline.TaskContext;
import com.waveminer.waveminer.pipeline.TaskEventPublisher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TranscribeStage implements StageHandler {

    private final EngineRegistry registry;
    private final FfmpegService ffmpeg;
    private final AudioSegmenter segmenter;
    private final TaskEventPublisher events;
    private final AppProperties props;

    public TranscribeStage(EngineRegistry registry, FfmpegService ffmpeg,
                           AudioSegmenter segmenter, TaskEventPublisher events, AppProperties props) {
        this.registry = registry;
        this.ffmpeg = ffmpeg;
        this.segmenter = segmenter;
        this.events = events;
        this.props = props;
    }

    @Override
    public StageType type() { return StageType.TRANSCRIBE; }

    @Override
    public void run(TaskContext ctx) {
        Path input = resolveInput(ctx);
        TranscriptionEngine engine = registry.get(ctx.engineId()); // 未知引擎 → EngineException
        if (ctx.cancelRequested()) {
            throw new TaskCancelledException();
        }
        long durationMs = Math.round(ffmpeg.probeDurationSeconds(input) * 1000);
        if (MediaTypes.isVideo(input.getFileName().toString())) {
            Path audio = ctx.dir().resolve("audio.m4a");
            ffmpeg.extractAudio(input, audio, ctx::cancelRequested);
            input = audio;
            durationMs = Math.round(ffmpeg.probeDurationSeconds(input) * 1000);
        }

        long maxDur = engine.info().maxDurationSec();
        TranscribeOptions options = TranscribeOptions.of(ctx.language(), ctx.diarize());
        List<Segment> segments;
        try {
            if (durationMs > maxDur * 1000) {
                segments = transcribeInChunks(ctx, engine, input, options, maxDur);
            } else {
                events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE", "正在调用转写引擎…"));
                TranscribeResult r = engine.transcribe(input, options, callback(ctx));
                archiveRaw(ctx, engine.id() + ".json", r.rawResponse());
                segments = r.segments();
            }
        } catch (EngineCancelledException e) {
            throw new TaskCancelledException();
        } catch (EngineException e) {
            if (e.getRawResponse() != null) {
                archiveRaw(ctx, engine.id() + ".json", e.getRawResponse());
            }
            throw e;
        }

        writeArtifacts(ctx, engine.id(), durationMs, segments);
        events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE",
                "转写完成：" + segments.size() + " 段"));
    }

    private Path resolveInput(TaskContext ctx) {
        Path input = ctx.artifactOf(StageType.AUDIO_EXTRACT);
        if (input == null) input = ctx.artifactOf(StageType.DOWNLOAD);
        if (input == null) input = ctx.uploadedFile().orElse(null);
        if (input == null) {
            throw new IllegalStateException("转写缺少音频/视频输入（需要上游产物或上传文件）");
        }
        return input;
    }

    private List<Segment> transcribeInChunks(TaskContext ctx, TranscriptionEngine engine,
                                             Path input, TranscribeOptions options, long maxDur) {
        Path chunksDir = ctx.dir().resolve("chunks");
        events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE",
                "音频超过引擎时长上限（" + maxDur + " 秒），开始静音切分"));
        List<AudioSegmenter.Chunk> chunks = segmenter.split(input, chunksDir, maxDur, ctx::cancelRequested);
        List<Segment> all = new ArrayList<>();
        try {
            int i = 1;
            for (AudioSegmenter.Chunk chunk : chunks) {
                if (ctx.cancelRequested()) {
                    throw new TaskCancelledException();
                }
                events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE",
                        "转写分片 " + i + "/" + chunks.size()));
                TranscribeResult r = engine.transcribe(chunk.file(), options, callback(ctx));
                archiveRaw(ctx, engine.id() + "-chunk-%03d.json".formatted(i), r.rawResponse());
                long offsetMs = Math.round(chunk.startSec() * 1000);
                for (Segment s : r.segments()) {
                    all.add(new Segment(s.startMs() + offsetMs, s.endMs() + offsetMs, s.text(), s.speaker()));
                }
                i++;
            }
        } catch (RuntimeException e) {
            // 失败保留分片目录便于排查
            throw e;
        }
        deleteRecursively(chunksDir);
        return SegmentMerger.merge(all, props.getMergeGapMs());
    }

    private void writeArtifacts(TaskContext ctx, String engineId, long durationMs, List<Segment> segments) {
        write(ctx.dir().resolve("transcript.json"),
                TranscriptFormat.toJson(engineId, ctx.language(), durationMs, segments));
        write(ctx.dir().resolve("transcript.srt"), TranscriptFormat.toSrt(segments));
        write(ctx.dir().resolve("transcript.txt"), TranscriptFormat.toTxt(segments));
        ctx.putArtifact(StageType.TRANSCRIBE, "transcript.json");
    }

    private ProgressCallback callback(TaskContext ctx) {
        return new ProgressCallback() {
            @Override public void update(String message) {
                events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE", message));
            }

            @Override public boolean cancelled() { return ctx.cancelRequested(); }
        };
    }

    private void archiveRaw(TaskContext ctx, String name, String raw) {
        if (raw == null) return;
        try {
            Path dir = Files.createDirectories(ctx.dir().resolve("engine_raw"));
            write(dir.resolve(name), raw);
        } catch (IOException e) {
            throw new UncheckedIOException("存档引擎原始响应失败", e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入产物失败: " + file.getFileName(), e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 清理失败不阻塞主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
```

- [ ] **Step 6: ArtifactStore / TaskController 修改**

`ArtifactStore.isAllowedArtifactName` 在末尾 `return false;` 前追加：

```java
        if (name.equals("transcript.json") || name.equals("transcript.srt") || name.equals("transcript.txt")) {
            return true;
        }
        if (name.startsWith("engine_raw/")) {
            String rest = name.substring("engine_raw/".length());
            return !rest.isBlank() && !rest.contains("/") && !rest.contains("..")
                    && rest.matches("[A-Za-z0-9._-]+");
        }
```

`ArtifactStore.listArtifacts` 改为：

```java
    public List<ArtifactInfo> listArtifacts(String taskId) {
        Path dir = taskDir(taskId);
        try (Stream<Path> files = Files.walk(dir, 2)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(this::isAllowedArtifactName)
                    .map(name -> new ArtifactInfo(name, sizeOf(dir.resolve(name))))
                    .sorted(Comparator.comparing(ArtifactInfo::name))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

（`replace('\\','/')` 处理 Windows 分隔符。）

`TaskController`：
- `@GetMapping("/{id}/artifacts/{name}")` → `@GetMapping("/{id}/artifacts/{name:.+}")`
- MIME 表追加三条：

```java
            Map.entry("json", MediaType.parseMediaType("application/json")),
            Map.entry("srt", MediaType.parseMediaType("application/x-subrip")),
            Map.entry("txt", MediaType.parseMediaType("text/plain;charset=UTF-8")));
```

- [ ] **Step 7: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add backend && git commit -m "feat: TRANSCRIBE 阶段、进度事件总线与转写产物白名单"
```

---

### Task 8: 阿里云百炼 filetrans 引擎（上传凭证 + OSS 上传 + 轮询）

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/engine/AliyunFiletransEngine.java`
- Modify: `config/EngineConfig`（追加 bean）
- Test: `backend/src/test/java/com/waveminer/waveminer/engine/AliyunFiletransEngineTest.java`

**Interfaces:**
- Consumes: `EngineHttpClient.send`、`MultipartBuilder`、`EngineKeyProvider`（Task 2/6）、`AppProperties.aliyunBaseUrl/aliyunModel/transcribePollIntervalSeconds/transcribeTimeoutSeconds`
- Produces:
  - `AliyunFiletransEngine implements TranscriptionEngine`（bean `aliyunFiletransEngine(EngineHttpClient, EngineKeyProvider, AppProperties)`）：
    - `id()="aliyun-filetrans"`；`info()=EngineInfo("aliyun-filetrans","阿里云百炼录音文件转写", 12*3600L, 2L*1024*1024*1024, true, List.of("zh","en","ja","yue","ko","de","fr","ru"))`
    - `record UploadPolicy(String uploadHost, String ossAccessKeyId, String signature, String policy, String uploadDir, String xOssObjectAcl, String xOssForbidOverwrite)`（嵌套 public record）
    - `static UploadPolicy parsePolicy(String json)`（包级静态供测试）：读 `output.data`；顶层含 `code` → `EngineException("获取上传凭证失败: {code} {message}", json)`
    - `static List<Segment> parseTranscription(String json)`（包级）：遍历 `transcripts[].sentences[]` → `Segment(begin_time, end_time, text, speaker_id?)`；缺 `transcripts` → `EngineException("识别结果缺少 transcripts 字段", json)`
    - `transcribe` 流程（ProgressCallback 报进度；每个阶段前 `if (progress.cancelled()) throw new EngineCancelledException("转写已取消")`）：
      1. key 缺失 → `EngineException("未配置阿里云百炼 API Key，请先在设置页填写")`
      2. `GET {base}/uploads?action=getPolicy&model={model}`（Bearer）→ 非 200 → `EngineException("获取上传凭证失败（HTTP n）", raw)`；`parsePolicy`
      3. `POST {upload_host}` multipart（字段顺序 `OSSAccessKeyId/Signature/policy/key/x-oss-object-acl/x-oss-forbid-overwrite/success_action_status=200/x-oss-content-type/file`；`key = uploadDir + "/" + filename`；`x-oss-content-type` 与 file 的 Content-Type 用按扩展名的 audio MIME，未知用 application/octet-stream）→ 非 2xx → `EngineException("上传音频到临时存储失败（HTTP n）", raw)`
      4. `POST {base}/services/audio/asr/transcription`，头 `Authorization: Bearer`、`Content-Type: application/json`、`X-DashScope-Async: enable`；body 用 ObjectNode 组装：`model`、`input.file_urls=["oss://"+objectKey]`、`parameters`（language≠auto → `language_hints:[language]`；`diarization_enabled=options.diarize()`）→ 非 200 → `EngineException("提交转写任务失败（HTTP n）", raw)`；`output.task_id` 缺失 → `EngineException("提交转写任务失败：响应缺少 task_id", raw)`
      5. 轮询 `POST {base}/tasks/{task_id}`（Bearer；间隔 `props.getTranscribePollIntervalSeconds()` 秒）：`task_status` PENDING/RUNNING → 继续；SUCCEEDED → 出循环；其余 → `EngineException("阿里云转写任务失败（{task_status}）: {code} {message}", raw)`；累计等待 > `props.getTranscribeTimeoutSeconds()` → `EngineException("转写任务超时（已等待 n 秒）")`
      6. `results[0]`：`subtask_status` 非 SUCCEEDED → `EngineException("识别子任务失败: {code} {message}", raw)`（results 为空同样报错）；否则无鉴权 GET `transcription_url` → 结果 JSON（非 200 → EngineException）
      7. rawResponse = 结果 JSON 文本
  - 引擎整体抛出的 `EngineException` 消息带"原始响应已存档到 engine_raw/"说明（提交/轮询/下载失败路径）

- [ ] **Step 1: 写解析单测（先失败）**

`engine/AliyunFiletransEngineTest.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunFiletransEngineTest {

    /** 官方 SDK OSSUtils 定义的 getPolicy 响应结构 */
    private static final String POLICY_JSON = """
            {
              "request_id": "r1",
              "output": {
                "data": {
                  "upload_host": "https://dashscope-file-transfer.oss-cn-beijing.aliyuncs.com",
                  "oss_access_key_id": "STS.xxx",
                  "signature": "sig123",
                  "policy": "pol123",
                  "upload_dir": "asr/filetrans/2026-10-21/abc",
                  "x_oss_object_acl": "private",
                  "x_oss_forbid_overwrite": "true"
                }
              }
            }""";

    /** 官方文档 transcription_url JSON 结构 */
    private static final String RESULT_JSON = """
            {
              "file_url": "oss://asr/filetrans/a.m4a",
              "properties": {"original_duration_in_milliseconds": 3834},
              "transcripts": [
                {
                  "channel_id": 0,
                  "text": "你好。好的。无说话人。",
                  "sentences": [
                    {"begin_time": 100, "end_time": 1440, "text": "你好。", "sentence_id": 1, "speaker_id": 0},
                    {"begin_time": 1440, "end_time": 3240, "text": "好的。", "sentence_id": 2, "speaker_id": 1},
                    {"begin_time": 4000, "end_time": 5000, "text": "无说话人。", "sentence_id": 3}
                  ]
                }
              ]
            }""";

    @Test
    void parsesUploadPolicy() {
        AliyunFiletransEngine.UploadPolicy p = AliyunFiletransEngine.parsePolicy(POLICY_JSON);
        assertThat(p.uploadHost()).isEqualTo("https://dashscope-file-transfer.oss-cn-beijing.aliyuncs.com");
        assertThat(p.ossAccessKeyId()).isEqualTo("STS.xxx");
        assertThat(p.signature()).isEqualTo("sig123");
        assertThat(p.policy()).isEqualTo("pol123");
        assertThat(p.uploadDir()).isEqualTo("asr/filetrans/2026-10-21/abc");
        assertThat(p.xOssObjectAcl()).isEqualTo("private");
        assertThat(p.xOssForbidOverwrite()).isEqualTo("true");
    }

    @Test
    void policyErrorFails() {
        assertThatThrownBy(() -> AliyunFiletransEngine.parsePolicy(
                        "{\"code\":\"InvalidApiKey\",\"message\":\"bad key\"}"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("上传凭证");
    }

    @Test
    void parsesTranscriptionSentences() {
        List<Segment> segments = AliyunFiletransEngine.parseTranscription(RESULT_JSON);
        assertThat(segments).containsExactly(
                new Segment(100, 1440, "你好。", 0),
                new Segment(1440, 3240, "好的。", 1),
                new Segment(4000, 5000, "无说话人。", null));
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=AliyunFiletransEngineTest`
Expected: FAIL，编译错误

- [ ] **Step 2: 实现解析方法与 UploadPolicy（让解析测试通过）**

```java
    public record UploadPolicy(String uploadHost, String ossAccessKeyId, String signature,
                               String policy, String uploadDir, String xOssObjectAcl,
                               String xOssForbidOverwrite) {}

    static UploadPolicy parsePolicy(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.hasNonNull("code")) {
                throw new EngineException("获取上传凭证失败: " + root.path("code").asText()
                        + " " + root.path("message").asText(""), json);
            }
            JsonNode data = root.path("output").path("data");
            if (data.isMissingNode() || data.path("upload_host").asText("").isBlank()) {
                throw new EngineException("获取上传凭证失败：响应缺少 output.data", json);
            }
            return new UploadPolicy(
                    data.path("upload_host").asText(),
                    data.path("oss_access_key_id").asText(),
                    data.path("signature").asText(),
                    data.path("policy").asText(),
                    data.path("upload_dir").asText(),
                    data.path("x_oss_object_acl").asText(),
                    data.path("x_oss_forbid_overwrite").asText());
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("上传凭证响应解析失败: " + e.getMessage(), e, json);
        }
    }

    static List<Segment> parseTranscription(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode transcripts = root.path("transcripts");
            if (transcripts.isMissingNode() || !transcripts.isArray()) {
                throw new EngineException("识别结果缺少 transcripts 字段", json);
            }
            List<Segment> out = new ArrayList<>();
            for (JsonNode t : transcripts) {
                for (JsonNode s : t.path("sentences")) {
                    Integer speaker = s.hasNonNull("speaker_id") ? s.path("speaker_id").asInt() : null;
                    out.add(new Segment(s.path("begin_time").asLong(), s.path("end_time").asLong(),
                            s.path("text").asText(""), speaker));
                }
            }
            return out;
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("识别结果解析失败: " + e.getMessage(), e, json);
        }
    }
```

Run: `cd backend && ./mvnw -q test -Dtest=AliyunFiletransEngineTest`
Expected: 前 3 个解析用例 PASS

- [ ] **Step 3: 写 WireMock 全流程测试（先失败，追加到同测试类）**

```java
    // —— 以下为全流程用例，追加 import： ——
    // import com.github.tomakehurst.wiremock.WireMockServer;
    // import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
    // import com.waveminer.waveminer.config.AppProperties;
    // import java.nio.file.Files; import java.nio.file.Path; import java.nio.file.Paths;
    // import org.junit.jupiter.api.io.TempDir;
    // import static com.github.tomakehurst.wiremock.client.WireMock.*;
    // import static org.mockito.Mockito.mock; import static org.mockito.Mockito.when;

    @TempDir
    Path tmpDir;

    private static final String SUBMIT_OK =
            "{\"request_id\":\"r\",\"output\":{\"task_id\":\"%s\",\"task_status\":\"PENDING\"}}";

    private AliyunFiletransEngine engine(WireMockServer wire, AppProperties props) {
        EngineKeyProvider keys = mock(EngineKeyProvider.class);
        when(keys.getKey("aliyun-filetrans")).thenReturn("sk-aliyun");
        return new AliyunFiletransEngine(new EngineHttpClient(props), keys, props);
    }

    private AppProperties props(WireMockServer wire) {
        AppProperties props = new AppProperties();
        props.setAliyunBaseUrl(wire.baseUrl() + "/api/v1");
        props.setTranscribePollIntervalSeconds(0); // 测试不等待
        return props;
    }

    @Test
    void fullFlowUploadSubmitPollDownload() throws Exception {
        WireMockServer wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        try {
            AppProperties props = props(wire);
            props.setAliyunModel("qwen-audio-3.0-asr-flash-filetrans");
            wire.stubFor(get(urlEqualTo(
                    "/api/v1/uploads?action=getPolicy&model=qwen-audio-3.0-asr-flash-filetrans"))
                    .willReturn(aResponse().withBody(POLICY_JSON)));
            wire.stubFor(post(urlEqualTo("/upload")).willReturn(aResponse().withStatus(200)));
            wire.stubFor(post(urlEqualTo("/api/v1/services/audio/asr/transcription"))
                    .willReturn(aResponse().withBody(String.format(SUBMIT_OK, "task-1"))));
            wire.stubFor(post(urlEqualTo("/api/v1/tasks/task-1"))
                    .inScenario("poll").whenScenarioStateIs("Started")
                    .willReturn(aResponse().withBody(
                            "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"PENDING\"}}"))
                    .willSetStateTo("second"));
            wire.stubFor(post(urlEqualTo("/api/v1/tasks/task-1"))
                    .inScenario("poll").whenScenarioStateIs("second")
                    .willReturn(aResponse().withBody("""
                            {"output":{"task_id":"task-1","task_status":"SUCCEEDED",
                              "results":[{"file_url":"oss://x/a.m4a","subtask_status":"SUCCEEDED",
                                "transcription_url":"%s/result.json"}]}}
                            """.formatted(wire.baseUrl())));
            wire.stubFor(get(urlEqualTo("/result.json"))
                    .willReturn(aResponse().withBody(RESULT_JSON)));

            AliyunFiletransEngine engine = engine(wire, props);
            Path audio = tmpDir.resolve("a.m4a");
            Files.writeString(audio, "audio-bytes");

            TranscribeResult r = engine.transcribe(audio, TranscribeOptions.of("zh", true), ProgressCallback.noop());

            assertThat(r.segments()).hasSize(3);
            assertThat(r.rawResponse()).contains("original_duration_in_milliseconds");

            var uploadEvent = wire.getAllServeEvents().stream()
                    .filter(e -> e.getRequest().getUrl().startsWith("/upload")).findFirst().orElseThrow();
            String body = new String(uploadEvent.getRequest().getBody());
            assertThat(body).contains("name=\"OSSAccessKeyId\"", "name=\"Signature\"", "name=\"policy\"")
                    .contains("name=\"key\"", "asr/filetrans/2026-10-21/abc/a.m4a")
                    .contains("name=\"success_action_status\"", "200")
                    .contains("name=\"x-oss-object-acl\"", "name=\"x-oss-forbid-overwrite\"");
            var submitEvent = wire.getAllServeEvents().stream()
                    .filter(e -> e.getRequest().getUrl().contains("/asr/transcription")).findFirst().orElseThrow();
            assertThat(submitEvent.getRequest().getHeader("X-DashScope-Async")).isEqualTo("enable");
            assertThat(submitEvent.getRequest().getHeader("Authorization")).isEqualTo("Bearer sk-aliyun");
            String submitBody = new String(submitEvent.getRequest().getBody());
            assertThat(submitBody).contains("oss://asr/filetrans/2026-10-21/abc/a.m4a")
                    .contains("language_hints").contains("diarization_enabled");
        } finally {
            wire.stop();
        }
    }

    @Test
    void subtaskFailureSurfacesCodeAndMessage() throws Exception {
        WireMockServer wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        try {
            wire.stubFor(get(urlMatching("/api/v1/uploads.*"))
                    .willReturn(aResponse().withBody(POLICY_JSON)));
            wire.stubFor(post(urlEqualTo("/upload")).willReturn(aResponse().withStatus(200)));
            wire.stubFor(post(urlEqualTo("/api/v1/services/audio/asr/transcription"))
                    .willReturn(aResponse().withBody(String.format(SUBMIT_OK, "task-2"))));
            wire.stubFor(post(urlEqualTo("/api/v1/tasks/task-2"))
                    .willReturn(aResponse().withBody("""
                            {"output":{"task_id":"task-2","task_status":"SUCCEEDED",
                              "results":[{"file_url":"oss://x/a.m4a","subtask_status":"FAILED",
                                "code":"InvalidFile.DownloadFailed","message":"The audio file cannot be downloaded."}]}}""")));

            AliyunFiletransEngine engine = engine(wire, props(wire));
            Path audio = tmpDir.resolve("b.m4a");
            Files.writeString(audio, "x");

            assertThatThrownBy(() -> engine.transcribe(audio, TranscribeOptions.of("auto", false),
                    ProgressCallback.noop()))
                    .isInstanceOf(EngineException.class)
                    .hasMessageContaining("InvalidFile.DownloadFailed");
        } finally {
            wire.stop();
        }
    }

    @Test
    void missingKeyFailsEarly() throws Exception {
        WireMockServer wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        try {
            EngineKeyProvider keys = mock(EngineKeyProvider.class);
            when(keys.getKey("aliyun-filetrans")).thenReturn(null);
            AliyunFiletransEngine engine = new AliyunFiletransEngine(new EngineHttpClient(props(wire)), keys, props(wire));
            Path audio = tmpDir.resolve("c.m4a");
            Files.writeString(audio, "x");
            assertThatThrownBy(() -> engine.transcribe(audio, TranscribeOptions.of("auto", false),
                    ProgressCallback.noop()))
                    .isInstanceOf(EngineException.class)
                    .hasMessageContaining("未配置阿里云百炼 API Key");
        } finally {
            wire.stop();
        }
    }
```

> **实现提示**：`RESULT_JSON`/`POLICY_JSON` 与解析用例共用（同类常量）；`props(wire)` 里 `setTranscribePollIntervalSeconds(0)` 后引擎轮询 sleep 应按 `Math.max(0, interval)` 处理。

Run: `cd backend && ./mvnw -q test -Dtest=AliyunFiletransEngineTest`
Expected: FAIL（transcribe 未实现）

- [ ] **Step 4: 实现 transcribe 主流程**

```java
public class AliyunFiletransEngine implements TranscriptionEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EngineHttpClient http;
    private final EngineKeyProvider keys;
    private final AppProperties props;

    public AliyunFiletransEngine(EngineHttpClient http, EngineKeyProvider keys, AppProperties props) {
        this.http = http;
        this.keys = keys;
        this.props = props;
    }

    @Override
    public String id() { return "aliyun-filetrans"; }

    @Override
    public EngineInfo info() {
        return new EngineInfo("aliyun-filetrans", "阿里云百炼录音文件转写",
                12 * 3600L, 2L * 1024 * 1024 * 1024, true,
                List.of("zh", "en", "ja", "yue", "ko", "de", "fr", "ru"));
    }

    @Override
    public TranscribeResult transcribe(Path mediaFile, TranscribeOptions options, ProgressCallback progress) {
        String key = keys.getKey(id());
        if (key == null || key.isBlank()) {
            throw new EngineException("未配置阿里云百炼 API Key，请先在设置页填写");
        }
        checkCancelled(progress);
        EngineHttpClient.EngineHttpResponse policyResp;
        try {
            progress.update("获取上传凭证…");
            policyResp = http.send(HttpRequest.newBuilder(URI.create(
                            props.getAliyunBaseUrl() + "/uploads?action=getPolicy&model=" + props.getAliyunModel()))
                    .timeout(props.engineHttpTimeout())
                    .header("Authorization", "Bearer " + key)
                    .GET().build());
        } catch (EngineException e) {
            throw new EngineException("获取上传凭证失败: " + e.getMessage(), e, null);
        }
        if (policyResp.statusCode() != 200) {
            throw new EngineException("获取上传凭证失败（HTTP " + policyResp.statusCode() + "）。"
                    + "原始响应已存档到 engine_raw/", policyResp.body());
        }
        UploadPolicy policy = parsePolicy(policyResp.body());

        checkCancelled(progress);
        progress.update("上传音频文件到临时存储…");
        String filename = mediaFile.getFileName().toString();
        String objectKey = policy.uploadDir() + "/" + filename;
        String contentType = contentTypeOf(filename);
        byte[] data;
        try {
            data = Files.readAllBytes(mediaFile);
        } catch (IOException e) {
            throw new EngineException("读取音频文件失败: " + e.getMessage(), e, null);
        }
        MultipartBuilder mp = new MultipartBuilder()
                .add("OSSAccessKeyId", policy.ossAccessKeyId())
                .add("Signature", policy.signature())
                .add("policy", policy.policy())
                .add("key", objectKey)
                .add("x-oss-object-acl", policy.xOssObjectAcl())
                .add("x-oss-forbid-overwrite", policy.xOssForbidOverwrite())
                .add("success_action_status", "200")
                .add("x-oss-content-type", contentType)
                .addFile("file", filename, contentType, data);
        EngineHttpClient.EngineHttpResponse upResp = http.send(HttpRequest.newBuilder(
                        URI.create(policy.uploadHost()))
                .timeout(props.engineHttpTimeout())
                .header("Content-Type", mp.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(mp.build())).build());
        if (upResp.statusCode() < 200 || upResp.statusCode() >= 300) {
            throw new EngineException("上传音频到临时存储失败（HTTP " + upResp.statusCode() + "）。"
                    + "原始响应已存档到 engine_raw/", upResp.body());
        }

        checkCancelled(progress);
        progress.update("提交转写任务…");
        EngineHttpClient.EngineHttpResponse submitResp = http.send(
                submitRequest(key, "oss://" + objectKey, options));
        if (submitResp.statusCode() != 200) {
            throw new EngineException("提交转写任务失败（HTTP " + submitResp.statusCode() + "）。"
                    + "原始响应已存档到 engine_raw/", submitResp.body());
        }
        String taskId;
        try {
            taskId = MAPPER.readTree(submitResp.body()).path("output").path("task_id").asText("");
        } catch (Exception e) {
            throw new EngineException("提交转写任务响应解析失败: " + e.getMessage(), e, submitResp.body());
        }
        if (taskId.isBlank()) {
            throw new EngineException("提交转写任务失败：响应缺少 task_id。原始响应已存档到 engine_raw/",
                    submitResp.body());
        }

        checkCancelled(progress);
        String resultJson = pollForResult(key, taskId);
        checkCancelled(progress);
        return new TranscribeResult(parseTranscription(resultJson), resultJson);
    }

    private HttpRequest submitRequest(String key, String fileUrl, TranscribeOptions options) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", props.getAliyunModel());
        ObjectNode input = root.putObject("input");
        input.putArray("file_urls").add(fileUrl);
        ObjectNode parameters = root.putObject("parameters");
        parameters.putArray("channel_id").add(0);
        if (!"auto".equals(options.language()) && options.language() != null && !options.language().isBlank()) {
            parameters.putArray("language_hints").add(options.language());
        }
        if (options.diarize()) {
            parameters.put("diarization_enabled", true);
        }
        return HttpRequest.newBuilder(URI.create(
                        props.getAliyunBaseUrl() + "/services/audio/asr/transcription"))
                .timeout(props.engineHttpTimeout())
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root)))
                .build();
    }

    private String pollForResult(String key, String taskId) {
        long deadline = System.currentTimeMillis() + props.getTranscribeTimeoutSeconds() * 1000;
        while (true) {
            EngineHttpClient.EngineHttpResponse resp;
            try {
                resp = http.send(HttpRequest.newBuilder(URI.create(
                                props.getAliyunBaseUrl() + "/tasks/" + taskId))
                        .timeout(props.engineHttpTimeout())
                        .header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody()).build());
            } catch (EngineException e) {
                throw new EngineException("查询转写任务失败: " + e.getMessage(), e, null);
            }
            if (resp.statusCode() != 200) {
                throw new EngineException("查询转写任务失败（HTTP " + resp.statusCode() + "）。"
                        + "原始响应已存档到 engine_raw/", resp.body());
            }
            JsonNode output;
            try {
                output = MAPPER.readTree(resp.body()).path("output");
            } catch (Exception e) {
                throw new EngineException("轮询响应解析失败: " + e.getMessage(), e, resp.body());
            }
            String status = output.path("task_status").asText("");
            switch (status) {
                case "PENDING", "RUNNING" -> { /* 继续 */ }
                case "SUCCEEDED" -> {
                    JsonNode results = output.path("results");
                    if (!results.isArray() || results.isEmpty()) {
                        throw new EngineException("转写任务成功但响应缺少 results。原始响应已存档到 engine_raw/",
                                resp.body());
                    }
                    JsonNode first = results.get(0);
                    if (!"SUCCEEDED".equals(first.path("subtask_status").asText(""))) {
                        throw new EngineException("识别子任务失败: "
                                + first.path("code").asText("") + " " + first.path("message").asText("")
                                + "。原始响应已存档到 engine_raw/", resp.body());
                    }
                    return downloadResult(first.path("transcription_url").asText(""));
                }
                default -> throw new EngineException("阿里云转写任务失败（" + status + "）: "
                        + output.path("code").asText("") + " " + output.path("message").asText("")
                        + "。原始响应已存档到 engine_raw/", resp.body());
            }
            if (System.currentTimeMillis() > deadline) {
                throw new EngineException("转写任务超时（已等待 " + props.getTranscribeTimeoutSeconds() + " 秒）");
            }
            try {
                Thread.sleep(Math.max(0, props.getTranscribePollIntervalSeconds()) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("轮询等待被中断", e, null);
            }
        }
    }

    private String downloadResult(String url) {
        if (url == null || url.isBlank()) {
            throw new EngineException("转写结果缺少 transcription_url");
        }
        EngineHttpClient.EngineHttpResponse resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(props.engineHttpTimeout()).GET().build());
        if (resp.statusCode() != 200) {
            throw new EngineException("下载转写结果失败（HTTP " + resp.statusCode() + "）");
        }
        return resp.body();
    }

    private static void checkCancelled(ProgressCallback progress) {
        if (progress.cancelled()) {
            throw new EngineCancelledException("转写已取消");
        }
    }

    private static String contentTypeOf(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "aac" -> "audio/aac";
            case "opus" -> "audio/opus";
            case "ogg" -> "audio/ogg";
            case "aiff" -> "audio/aiff";
            default -> "application/octet-stream";
        };
    }
}
```

（import：`java.io.IOException`、`java.net.URI`、`java.net.http.HttpRequest`、`java.nio.file.Files/Path`、`java.util.ArrayList/List`、`tools.jackson.databind.JsonNode/ObjectMapper/node.ObjectNode`。）

`EngineConfig` 追加 bean：

```java
    @Bean
    public AliyunFiletransEngine aliyunFiletransEngine(EngineHttpClient http, EngineKeyProvider keys,
                                                       AppProperties props) {
        return new AliyunFiletransEngine(http, keys, props);
    }
```

- [ ] **Step 5: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend && git commit -m "feat: 阿里云百炼录音文件转写引擎（上传凭证+OSS+轮询）"
```

---

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

### Task 10: 前端扩展（一）：类型/客户端/时间轴工具 + 新建任务转写卡

**Files:**
- Modify: `frontend/src/api/types.ts`、`frontend/src/api/client.ts`、`frontend/src/views/NewTaskView.vue`
- Create: `frontend/src/utils/timeline.ts`
- Test: `frontend/src/utils/timeline.test.ts`

**Interfaces:**
- Consumes: Task 2/3/9 的 HTTP 契约（`/api/engines`、`/api/settings`、`/api/tasks` 新参数、`/api/tasks/{id}/events`）
- Produces:
  - `types.ts` 新增：`interface EngineInfo { id; displayName; maxDurationSec; maxSizeBytes; supportsDiarization; languages: string[]; configured: boolean }`、`interface TranscriptSegment { startMs: number; endMs: number; text: string; speaker?: number | null }`、`interface Transcript { segments: TranscriptSegment[]; engine: string; language: string; durationMs: number }`、`interface SettingView { key: string; value: string; configured: boolean }`；`TaskView` 增加 `engineId: string | null; language: string | null; diarize: boolean`
  - `client.ts` 新增：`getEngines(): Promise<EngineInfo[]>`、`getSettings(): Promise<SettingView[]>`、`putSetting(key, value): Promise<SettingView>`（PUT JSON）、`sseUrl(taskId): string`（`/api/tasks/{id}/events` + `?token=`）
  - `utils/timeline.ts`：
    - `formatTimecode(ms: number): string` → `mm:ss`（≥1h `h:mm:ss`，与后端 TranscriptFormat.timecode 一致）
    - `findActiveSegment(segments: TranscriptSegment[], timeMs: number): number` → 命中 `[startMs,endMs)` 的下标，无则 -1
    - `clampSeekMs(seg: TranscriptSegment): number` → `Math.max(0, seg.startMs)`
  - `NewTaskView.vue`：勾选 `TRANSCRIBE` 时显示：引擎下拉（`getEngines()`，label `displayName +（≤X秒/YMB）`，`configured=false` 时警告"未配置 API Key"）、语言下拉（`自动检测` + engine.languages）、"说话人分离" checkbox（仅 `engine.supportsDiarization` 时显示）；提交表单附 `engineId/language/diarize` 字段

- [ ] **Step 1: 写 timeline 测试（先失败）**

`frontend/src/utils/timeline.test.ts`：

```ts
import { describe, expect, it } from 'vitest'
import { clampSeekMs, findActiveSegment, formatTimecode } from './timeline'
import type { TranscriptSegment } from '../api/types'

const segments: TranscriptSegment[] = [
  { startMs: 0, endMs: 1660, text: 'a', speaker: 1 },
  { startMs: 2000, endMs: 6100, text: 'b', speaker: 2 },
  { startMs: 65000, endMs: 70000, text: 'c', speaker: null },
]

describe('formatTimecode', () => {
  it('formats mm:ss', () => {
    expect(formatTimecode(0)).toBe('00:00')
    expect(formatTimecode(65_000)).toBe('01:05')
    expect(formatTimecode(3_723_000)).toBe('1:02:03')
  })
})

describe('findActiveSegment', () => {
  it('finds segment containing time', () => {
    expect(findActiveSegment(segments, 1000)).toBe(0)
    expect(findActiveSegment(segments, 2100)).toBe(1)
    expect(findActiveSegment(segments, 65_500)).toBe(2)
  })
  it('returns -1 in gaps and out of range', () => {
    expect(findActiveSegment(segments, 1800)).toBe(-1)
    expect(findActiveSegment(segments, -1)).toBe(-1)
    expect(findActiveSegment(segments, 999_999)).toBe(-1)
  })
})

describe('clampSeekMs', () => {
  it('clamps to zero', () => {
    expect(clampSeekMs(segments[0])).toBe(0)
    expect(clampSeekMs({ startMs: -5, endMs: 0, text: '' })).toBe(0)
  })
})
```

Run: `cd frontend && npm run test`
Expected: FAIL（模块不存在）

- [ ] **Step 2: 实现 timeline.ts**

`frontend/src/utils/timeline.ts`：

```ts
import type { TranscriptSegment } from '../api/types'

export function formatTimecode(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '00:00'
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

export function findActiveSegment(segments: TranscriptSegment[], timeMs: number): number {
  for (let i = 0; i < segments.length; i++) {
    if (timeMs >= segments[i].startMs && timeMs < segments[i].endMs) return i
  }
  return -1
}

export function clampSeekMs(seg: TranscriptSegment): number {
  return Math.max(0, seg.startMs)
}
```

Run: `cd frontend && npm run test`
Expected: PASS

- [ ] **Step 3: 扩展 types.ts / client.ts**

`types.ts` 追加（并给 `TaskView` 增加 3 个可选字段以匹配后端）：

```ts
export interface EngineInfo {
  id: string
  displayName: string
  maxDurationSec: number
  maxSizeBytes: number
  supportsDiarization: boolean
  languages: string[]
  configured: boolean
}

export interface TranscriptSegment {
  startMs: number
  endMs: number
  text: string
  speaker?: number | null
}

export interface Transcript {
  segments: TranscriptSegment[]
  engine: string
  language: string
  durationMs: number
}

export interface SettingView {
  key: string
  value: string
  configured: boolean
}
```

`TaskView` 中 `errorMessage: string | null` 之后插入：

```ts
  engineId: string | null
  language: string | null
  diarize: boolean
```

`client.ts` 的 `api` 对象追加：

```ts
  getEngines(): Promise<EngineInfo[]> {
    return fetch('/api/engines', { headers: headers() }).then((r) => parse<EngineInfo[]>(r))
  },
  getSettings(): Promise<SettingView[]> {
    return fetch('/api/settings', { headers: headers() }).then((r) => parse<SettingView[]>(r))
  },
  putSetting(key: string, value: string): Promise<SettingView> {
    return fetch('/api/settings', {
      method: 'PUT',
      headers: { ...headers(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ key, value }),
    }).then((r) => parse<SettingView>(r))
  },
  sseUrl(taskId: string): string {
    const t = getToken()
    return `/api/tasks/${taskId}/events${t ? `?token=${encodeURIComponent(t)}` : ''}`
  },
```

（import 行加 `EngineInfo, SettingView` 类型。）

- [ ] **Step 4: NewTaskView 加转写卡**

`NewTaskView.vue` 的 `<script setup>` 增加：

```ts
import { api } from '../api/client'
import type { EngineInfo } from '../api/types'

const engines = ref<EngineInfo[]>([])
const engineId = ref('minimax')
const language = ref('auto')
const diarize = ref(false)
const hasTranscribe = computed(() => stages.value.includes('TRANSCRIBE'))
const currentEngine = computed(() => engines.value.find((e) => e.id === engineId.value) ?? null)
const languageOptions = computed(() => ['auto', ...(currentEngine.value?.languages ?? [])])

const LANG_LABELS: Record<string, string> = {
  auto: '自动检测', zh: '中文', yue: '粤语', en: '英语', ja: '日语', ko: '韩语', th: '泰语',
  vi: '越南语', id: '印尼语', ms: '马来语', fil: '菲律宾语', ar: '阿拉伯语', tr: '土耳其语',
  fr: '法语', de: '德语', es: '西班牙语', it: '意大利语', pt: '葡萄牙语', pl: '波兰语',
  ru: '俄语', uk: '乌克兰语',
}

async function loadEngines() {
  try {
    engines.value = await api.getEngines()
    if (!engines.value.some((e) => e.id === engineId.value) && engines.value.length > 0) {
      engineId.value = engines.value[0].id
    }
  } catch { /* 忽略，提交时后端仍会校验 */ }
}

watch(engineId, () => {
  if (!languageOptions.value.includes(language.value)) language.value = 'auto'
})

onMounted(loadEngines)
```

（`ref/computed/watch/onMounted` 若未导入需补齐。）

勾选组追加 TRANSCRIBE：

```html
<el-checkbox value="TRANSCRIBE">转写（输入：音频/视频）</el-checkbox>
```

勾选组下方、提交按钮之前插入转写配置卡片：

```html
<el-card v-if="hasTranscribe" shadow="never" style="margin-bottom: 16px">
  <template #header>转写设置</template>
  <el-form label-width="110px">
    <el-form-item label="转写引擎">
      <el-select v-model="engineId" style="width: 320px">
        <el-option
          v-for="e in engines"
          :key="e.id"
          :value="e.id"
          :label="`${e.displayName}（≤${Math.round(e.maxDurationSec / 60)} 分钟 / ${Math.round(e.maxSizeBytes / 1024 / 1024)}MB）`"
        />
      </el-select>
      <el-alert
        v-if="currentEngine && !currentEngine.configured"
        type="warning"
        :closable="false"
        show-icon
        title="该引擎尚未配置 API Key，请到设置页填写或配置环境变量"
        style="margin-top: 8px"
      />
    </el-form-item>
    <el-form-item label="语言">
      <el-select v-model="language" style="width: 320px">
        <el-option v-for="l in languageOptions" :key="l" :value="l" :label="LANG_LABELS[l] ?? l" />
      </el-select>
    </el-form-item>
    <el-form-item v-if="currentEngine?.supportsDiarization" label="说话人分离">
      <el-switch v-model="diarize" />
    </el-form-item>
  </el-form>
</el-card>
```

`submit()` 的 FormData 组装处追加：

```ts
  if (hasTranscribe.value) {
    form.set('engineId', engineId.value)
    form.set('language', language.value)
    form.set('diarize', String(diarize.value))
  }
```

- [ ] **Step 5: 构建验证**

Run: `cd frontend && npm run test && npm run build`
Expected: vitest 全过；`vue-tsc && vite build` 干净

- [ ] **Step 6: Commit**

```bash
git add frontend && git commit -m "feat: 新建任务转写引擎/语言配置与时间轴工具"
```

---

### Task 11: 前端扩展（二）：任务详情时间轴对照 + SSE 订阅 + engine_raw 查看器

**Files:**
- Modify: `frontend/src/views/TaskDetailView.vue`、`frontend/src/api/client.ts`（artifactUrl 分段编码）
- Test: 无新增组件测试（`vue-tsc && vite build` + vitest 回归）

**Interfaces:**
- Consumes: Task 9 SSE（`event: progress`，data JSON `{taskId,type,stage,status,message}`；`type=="task"` 为终态，服务端随后 complete）、Task 10 类型/工具、`transcript.json`/`engine_raw/*` 产物下载接口（`{name:.+}`）
- Produces:
  - `client.ts` 的 `artifactUrl` 改为按 `/` 分段编码：`name.split('/').map(encodeURIComponent).join('/')`（其余逻辑不变）
  - 详情页新增"时间轴对照"卡片（任务有 `transcript.json` 产物时显示）：
    - 加载 `api.artifactUrl(id, 'transcript.json')` → `resp.json()` → `Transcript`
    - 左侧 `<audio>`/`<video>`（优先 `audio.m4a`，其次现有 playableArtifact 逻辑），`@timeupdate` → `activeIndex = findActiveSegment(segments, t*1000)`，当前段高亮且滚动到可视区
    - 右侧分段列表：`[formatTimecode(startMs) - formatTimecode(endMs)]` + 说话人标签（`speaker!=null` → `说话人 {speaker+1}`）+ 文本；点击段 → `media.currentTime = clampSeekMs(seg)/1000` 并播放
  - SSE：`onMounted` 建 `new EventSource(api.sseUrl(id))`；`onmessage`（默认消息）→ 解析 JSON：`type=="task"` 终态 → `load()` 后 `es.close()` 并停止；否则防抖 300ms `load()`；`onerror` → `es.close()` 并启用 2s 轮询兜底（任务终态后自动停止轮询）
  - engine_raw 查看器：产物列表中 `engine_raw/` 开头的文件提供"查看"按钮 → dialog 内 `<pre>` 显示 JSON 文本（`artifactUrl` 拉取）

- [ ] **Step 1: client.ts artifactUrl 分段编码**

```ts
  artifactUrl(taskId: string, name: string): string {
    const t = getToken()
    const encoded = name.split('/').map(encodeURIComponent).join('/')
    return `/api/tasks/${taskId}/artifacts/${encoded}${t ? `?token=${encodeURIComponent(t)}` : ''}`
  },
```

- [ ] **Step 2: TaskDetailView 增加 SSE（替换轮询主逻辑）**

`<script setup>` 关键改动（保留现有 load/retry/cancel）：

```ts
import type { TaskView, Transcript, TranscriptSegment } from '../api/types'
import { clampSeekMs, findActiveSegment, formatTimecode } from '../utils/timeline'

const transcript = ref<Transcript | null>(null)
const activeIndex = ref(-1)
const rawOpen = ref(false)
const rawName = ref('')
const rawContent = ref('')
const mediaRef = ref<HTMLMediaElement | null>(null)
let es: EventSource | null = null
let pollTimer: number | undefined
let debounceTimer: number | undefined

const transcriptArtifact = computed(() =>
  task.value?.artifacts.find((a) => a.name === 'transcript.json') ?? null)
const mediaArtifact = computed(() => {
  if (!task.value) return null
  return task.value.artifacts.find((a) => a.name === 'audio.m4a')
    ?? task.value.artifacts.find((a) => /\.(mp4|mkv|webm|mov|m4v|mp3|m4a|wav|flac|ogg)$/i.test(a.name))
    ?? null
})
const rawArtifacts = computed(() =>
  task.value?.artifacts.filter((a) => a.name.startsWith('engine_raw/')) ?? [])

async function loadTranscript() {
  if (!transcriptArtifact.value) { transcript.value = null; return }
  try {
    const resp = await fetch(api.artifactUrl(id, 'transcript.json'))
    transcript.value = resp.ok ? await resp.json() : null
  } catch { transcript.value = null }
}

function onTimeUpdate() {
  const media = mediaRef.value
  if (!media || !transcript.value) return
  activeIndex.value = findActiveSegment(transcript.value.segments, media.currentTime * 1000)
}

function seekTo(seg: TranscriptSegment) {
  const media = mediaRef.value
  if (!media) return
  media.currentTime = clampSeekMs(seg) / 1000
  media.play().catch(() => { /* 自动播放被拦截时可忽略 */ })
}

function speakerLabel(s: number | null | undefined): string {
  return s == null ? '' : `说话人 ${s + 1}`
}

async function showRaw(name: string) {
  rawName.value = name
  rawOpen.value = true
  try {
    const resp = await fetch(api.artifactUrl(id, name))
    rawContent.value = resp.ok ? JSON.stringify(await resp.json(), null, 2) : '加载失败'
  } catch (e) {
    rawContent.value = '加载失败: ' + (e as Error).message
  }
}

function startSse() {
  es = new EventSource(api.sseUrl(id))
  es.onmessage = (ev) => {
    try {
      const e = JSON.parse(ev.data)
      if (e.type === 'task') {
        es?.close()
        es = null
        load()
        return
      }
      window.clearTimeout(debounceTimer)
      debounceTimer = window.setTimeout(load, 300)
    } catch { /* 忽略非 JSON 帧 */ }
  }
  es.onerror = () => {
    es?.close()
    es = null
    startPolling() // SSE 断开 → 轮询兜底
  }
}

function startPolling() {
  if (pollTimer !== undefined) return
  pollTimer = window.setInterval(() => {
    load().then(() => {
      if (task.value && task.value.status !== 'RUNNING' && task.value.status !== 'PENDING') {
        stopPolling()
      }
    })
  }, 2000)
}

function stopPolling() {
  if (pollTimer !== undefined) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

onMounted(() => {
  load().then(loadTranscript)
  startSse()
})

onBeforeUnmount(() => {
  es?.close()
  stopPolling()
  window.clearTimeout(debounceTimer)
})
```

`load()` 改为同时刷新转写：成功路径末尾 `await loadTranscript()`；模板中进度卡沿用；原 `setInterval(load, 2000)` 轮询初始化删除（由 SSE/兜底接管）。

- [ ] **Step 3: 模板增加时间轴对照与 engine_raw 查看器**

产物卡片之前插入：

```html
<el-card v-if="transcript" style="margin-top: 16px">
  <template #header>
    时间轴对照 · 引擎 {{ transcript.engine }} · 语言 {{ transcript.language }}
  </template>
  <div style="display: flex; gap: 16px; align-items: flex-start">
    <div style="flex: 1; min-width: 0">
      <video
        v-if="mediaArtifact && /\.(mp4|mkv|webm|mov|m4v)$/i.test(mediaArtifact.name)"
        ref="mediaRef"
        :src="api.artifactUrl(id, mediaArtifact.name)"
        controls
        @timeupdate="onTimeUpdate"
        style="width: 100%; max-height: 420px"
      />
      <audio
        v-else-if="mediaArtifact"
        ref="mediaRef"
        :src="api.artifactUrl(id, mediaArtifact.name)"
        controls
        @timeupdate="onTimeUpdate"
        style="width: 100%"
      />
    </div>
    <div style="width: 380px; max-height: 420px; overflow-y: auto">
      <div
        v-for="(seg, i) in transcript.segments"
        :key="i"
        @click="seekTo(seg)"
        :style="{
          padding: '6px 10px', cursor: 'pointer', borderRadius: '6px',
          background: i === activeIndex ? 'var(--el-color-primary-light-9)' : 'transparent',
          borderLeft: i === activeIndex ? '3px solid var(--el-color-primary)' : '3px solid transparent',
        }"
      >
        <div style="font-size: 12px; color: #909399">
          [{{ formatTimecode(seg.startMs) }} - {{ formatTimecode(seg.endMs) }}]
          <el-tag v-if="seg.speaker != null" size="small" style="margin-left: 6px">
            {{ speakerLabel(seg.speaker) }}
          </el-tag>
        </div>
        <div>{{ seg.text }}</div>
      </div>
    </div>
  </div>
</el-card>
```

产物表格"操作"列追加（`engine_raw/` 文件显示"查看"）：

```html
<el-button v-if="row.name.startsWith('engine_raw/')" size="small" @click="showRaw(row.name)">查看</el-button>
```

页面底部追加对话框：

```html
<el-dialog v-model="rawOpen" :title="rawName" width="720px">
  <pre style="max-height: 60vh; overflow: auto; white-space: pre-wrap; word-break: break-all">{{ rawContent }}</pre>
</el-dialog>
```

- [ ] **Step 4: 构建验证**

Run: `cd frontend && npm run test && npm run build`
Expected: vitest 全过；`vue-tsc && vite build` 干净

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: 任务详情时间轴对照、SSE 进度与引擎原始响应查看"
```

---

### Task 12: 配置文档化（README/application.yml 注释）+ 打包冒烟

**Files:**
- Modify: `README.md`（转写章节）

**Interfaces:**
- Consumes: 全部后端配置键（Task 5 yml 已加）

- [ ] **Step 1: README 增加"转写"章节**

在 README 适当位置追加：

```markdown
## 转写（M3+M4）

任务创建时勾选"转写"，选择引擎与语言，任务完成后详情页提供时间轴对照与 `transcript.json/.srt/.txt` 三种产物下载。

### 引擎与 API Key

| 引擎 | 引擎 ID | 限制 | Key 配置 |
|---|---|---|---|
| MiniMax 语音识别 | `minimax` | ≤500 秒/50MB（超长自动静音切分） | 设置页 `minimax.apiKey` 或环境变量 `WAVEMINER_MINIMAX_API_KEY` |
| 阿里云百炼录音文件转写 | `aliyun-filetrans` | ≤12 小时/2GB | 设置页 `aliyun.apiKey` 或环境变量 `WAVEMINER_ALIYUN_API_KEY`（兼容 `DASHSCOPE_API_KEY`） |

- MiniMax baseUrl 默认中国站 `https://api.minimax.cn`，可用 `APP_MINIMAX_BASE_URL` 覆盖（国际站 `https://api.minimax.io`）。
- 阿里云 baseUrl 默认 `https://dashscope.aliyuncs.com/api/v1`，可用 `APP_ALIYUN_BASE_URL` 指向业务空间专属域名；默认模型 `qwen-audio-3.0-asr-flash-filetrans`（`APP_ALIYUN_MODEL` 可换 `paraformer-v2` 等）。
- 本地文件转写（阿里云）走 DashScope 上传凭证 + `oss://` 临时 URL，凭证限流 100 QPS。

### 相关配置（application.yml / 环境变量）

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.engine-http-timeout-seconds` | 600 | 引擎单次 HTTP 请求超时 |
| `app.engine-retry-attempts` | 3 | 429/5xx/网络错误重试次数 |
| `app.transcribe-poll-interval-seconds` | 5 | 阿里云任务轮询间隔 |
| `app.transcribe-timeout-seconds` | 7200 | 转写任务整体超时 |
| `app.silence-threshold-db` / `app.silence-min-duration-sec` | -35 / 0.6 | 静音切分阈值 |
| `app.merge-gap-ms` | 800 | 跨分片段落合并间隙 |

### 进度推送

任务详情页使用 SSE（`GET /api/tasks/{id}/events`），断开自动回退轮询。访问令牌启用时 EventSource 走 `?token=`。
```

- [ ] **Step 2: 打包冒烟**

```bash
cd frontend && npm run build && cd ../backend && ./mvnw -q package -DskipTests
java -jar target/wave-miner-0.0.1-SNAPSHOT.jar &
sleep 8
curl -sf http://127.0.0.1:8080/api/engines | head -c 400
curl -sf http://127.0.0.1:8080/api/settings | head -c 200
kill %1
```

Expected: `/api/engines` 返回两个引擎 JSON（`configured` 视 key 而定）；`/api/settings` 返回两个设置项。

- [ ] **Step 3: Commit**

```bash
git add README.md && git commit -m "docs: 转写引擎配置与使用说明"
```

---

### Task 13: 全量测试 + 真机验收（真实 API Key）+ HANDOFF 更新

**Files:**
- Modify: `docs/superpowers/HANDOFF-PLAN2.md`（状态更新）

**Interfaces:**
- Consumes: 完整系统（Task 1-12）+ 需求方提供的真实 MiniMax 中国站 / 阿里云百炼 API Key

- [ ] **Step 1: 全量自动化测试**

```bash
cd backend && ./mvnw -q test
cd ../frontend && npm run test && npm run build
```

Expected: 后端全部 PASS、前端 vitest 全过 + 构建干净。

- [ ] **Step 2: 真机验收（真实 key，逐项记录结果）**

启动：`cd backend && ./mvnw spring-boot:run`（或 jar），设置页填入真实 key。

1. 设置页：保存 MiniMax/阿里云 key → GET `/api/settings` 显示脱敏值、`configured=true`；清空 → `configured=false`
2. `GET /api/engines`：两引擎均 `configured=true`
3. 短音频（<500s）MiniMax 转写：上传 mp3/m4a 勾选"转写"→ COMPLETED → transcript.json/srt/txt 可下载、内容与音频一致 → 详情页时间轴对照可播放联动（点击段落跳转、播放高亮）
4. 中英混合 + 说话人分离（MiniMax verbose_json 天然支持）：说话人标签正确显示
5. 直链 mp4 全流程（下载+提取音频+转写，组合①+③）：视频输入自动提音频后转写成功
6. 阿里云引擎同一音频转写：双引擎切换可用，`engine_raw/aliyun-filetrans.json` 内容为 transcription_url 结果 JSON；上传凭证→OSS→轮询无错误
7. >500s 音频 + MiniMax：静音切分触发（进度显示"转写分片 i/n"），产物时间戳连续正确，`chunks/` 目录已清理
8. 未配置 key 时创建转写任务：任务 FAILED，错误信息中文且包含"未配置 ... API Key"
9. SSE：详情页打开时另一终端触发任务，进度实时刷新无手动刷新；服务端日志无 SSE 异常
10. 令牌开启（`APP_ACCESS_TOKEN=x` 重启）：SSE `?token=` 正常收到事件；错误令牌 401
11. 错误路径：MiniMax 填一个错误 key → 任务 FAILED，`engine_raw/minimax.json` 有原始错误响应，错误信息中文

- [ ] **Step 3: 验收问题修复**

发现的问题按 TDD 小步修复并各自提交（`fix: ...`）；无法当场修复的记录到 HANDOFF 遗留清单。

- [ ] **Step 4: 更新 HANDOFF 文档状态**

`docs/superpowers/HANDOFF-PLAN2.md` 更新：
- 头部"最后更新"改为本次日期，标注"计划 2（M3+M4+M5）完成并验收"
- §1 现状追加：转写引擎层（MiniMax + 阿里云）、TRANSCRIBE stage、三种产物、SSE、时间轴对照 UI 已交付；测试数量更新为实际值
- §3 计划 2 范围标记完成；计划 3（M6）改为"下一步"
- §6 遗留清单：合并本次验收新发现（若有）
- 追加"计划 2 Ruling 摘要"引用本计划文档路径

- [ ] **Step 5: 最终提交**

```bash
git add docs/superpowers/HANDOFF-PLAN2.md && git commit -m "docs: 计划 2（M3+M4+M5）完成状态更新"
```

---

## 计划 2 覆盖的规格条目

- §5.1 引擎 SPI（接口/归一化/统一句级输出）→ Task 2
- §5.2 MiniMax 引擎（请求/响应/限制/中国站 baseUrl）→ Task 6
- §5.3 阿里云百炼引擎（上传凭证→OSS→提交→轮询→结果归一化）→ Task 8
- §5.5 超长音频分片（silencedetect→切分→偏移归并 <800ms）→ Task 5 + Task 7
- §5.6 重试与限流（429/5xx 指数退避、engine_raw 存档）→ Task 6（EngineHttpClient）+ Task 7/8（存档）
- §4.3 三种产物 + engine_raw → Task 4（格式器）+ Task 7（落盘与白名单/MIME）
- §7 `GET /api/engines` → Task 2；`GET/PUT /api/settings` → Task 1；`GET /api/tasks/{id}/events` → Task 9；任务创建新参数 → Task 3
- §6 任务模型扩展（engineId/language/diarize 持久化）→ Task 3
- §9 前端（新建任务转写卡、时间轴对照、SSE、engine_raw 查看、断线回退轮询）→ Task 10/11
- §11 测试策略（官方响应样例 fixture、WireMock 模拟两引擎含失败/重试路径、切分归并、SRT/TXT 生成、Mock 引擎全链）→ Task 2-9 各测试
- §13 实现期核对清单 1/2/3 → 本计划头部"官方 API 核对结论"（已完成核对：上传凭证端点/字段、qwen filetrans 参数、MiniMax 中国站 baseUrl）
- §14 跨平台（测试不依赖 Unix 命令、ProcessRunner 封装、路径分隔符处理、配置环境变量化）→ Task 5/7 与 Global Constraints
- §0 交接约定（官方文档为准、Ruling 记录、中文错误、双平台、虚拟线程、无 Lombok）→ 全计划
