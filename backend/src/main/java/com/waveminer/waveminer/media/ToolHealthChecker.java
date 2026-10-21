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
