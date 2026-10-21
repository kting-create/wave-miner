package com.waveminer.waveminer.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.waveminer.waveminer.config.AppProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolHealthCheckerTest {

    private final List<List<String>> commands = new ArrayList<>();

    private ToolHealthChecker checker(ProcessResult result) {
        return new ToolHealthChecker(new ProcessRunner() {
            @Override
            public ProcessResult run(List<String> command, Duration timeout) {
                commands.add(command);
                return result;
            }
        }, new AppProperties());
    }

    @Test
    void selectsVersionFlagPerTool() {
        checker(new ProcessResult(0, "banner")).checkAll();
        AppProperties props = new AppProperties();
        assertThat(commands).containsExactly(
                List.of(props.getFfmpegPath(), "-version"),
                List.of(props.getFfprobePath(), "-version"),
                List.of(props.getYtDlpPath(), "--version"));
    }

    @Test
    void ffmpegVersionExitZeroIsOk() {
        ToolHealthChecker.ToolHealth h = checker(new ProcessResult(0, "ffmpeg version 9.0.2\n"))
                .checkAll().get(0);
        assertThat(h.name()).isEqualTo("ffmpeg");
        assertThat(h.ok()).isTrue();
        assertThat(h.version()).isEqualTo("ffmpeg version 9.0.2");
    }

    @Test
    void nonZeroExitReportsUnavailable() {
        ToolHealthChecker.ToolHealth h = checker(new ProcessResult(8, "Unrecognized option '-version'."))
                .checkAll().get(0);
        assertThat(h.ok()).isFalse();
        assertThat(h.version()).isEqualTo("不可用");
    }
}
