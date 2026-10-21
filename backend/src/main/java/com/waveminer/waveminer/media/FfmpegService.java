package com.waveminer.waveminer.media;

import com.waveminer.waveminer.config.AppProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class FfmpegService {

    public interface Runner {
        ProcessResult run(List<String> command, Duration timeout, BooleanSupplier cancelSignal);

        default ProcessResult run(List<String> command, Duration timeout) {
            return run(command, timeout, null);
        }
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

    public boolean hasAudioStream(Path file) {
        List<String> command = List.of(props.getFfprobePath(), "-v", "error", "-select_streams", "a",
                "-show_entries", "stream=index", "-of", "csv=p=0", file.toString());
        ProcessResult r = runner.run(command, props.processTimeout());
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("ffprobe 探测音轨失败:\n" + tail(r.output()));
        }
        return !r.output().isBlank();
    }

    public void extractAudio(Path in, Path out) {
        extractAudio(in, out, null);
    }

    public void extractAudio(Path in, Path out, BooleanSupplier cancelSignal) {
        ProcessResult r = runner.run(extractAudioCommand(in, out), props.processTimeout(), cancelSignal);
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("ffmpeg 提取音频失败:\n" + tail(r.output()));
        }
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }
}
