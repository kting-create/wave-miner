package com.waveminer.waveminer.engine;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.waveminer.waveminer.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    // —— 全流程用例 ——

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

    /** 全流程用例将 upload_host 指向 WireMock，避免测试请求真实 OSS 外网地址 */
    private String hermeticPolicy(WireMockServer wire) {
        return POLICY_JSON.replace("https://dashscope-file-transfer.oss-cn-beijing.aliyuncs.com",
                wire.baseUrl() + "/upload");
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
                    .willReturn(aResponse().withBody(hermeticPolicy(wire))));
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
                            """.formatted(wire.baseUrl()))));
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
                    .willReturn(aResponse().withBody(hermeticPolicy(wire))));
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
}
