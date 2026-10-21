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
import tools.jackson.databind.node.ObjectNode;

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

    public record UploadPolicy(String uploadHost, String ossAccessKeyId, String signature,
                               String policy, String uploadDir, String xOssObjectAcl,
                               String xOssForbidOverwrite) {}

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
}
