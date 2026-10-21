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

