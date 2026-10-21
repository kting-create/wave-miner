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
