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

