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
                .inScenario("retry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("ok")
                .willReturn(aResponse().withStatus(429)
                        .withBody("{\"type\":\"error\",\"error\":{\"message\":\"rate limit\"}}")));
        wire.stubFor(post(urlEqualTo("/v1/speech_to_text"))
                .inScenario("retry")
                .whenScenarioStateIs("ok")
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
