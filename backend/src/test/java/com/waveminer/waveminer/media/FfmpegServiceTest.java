package com.waveminer.waveminer.media;

import com.waveminer.waveminer.config.AppProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfmpegServiceTest {

    private List<String> lastCommand;

    private final FfmpegService service = new FfmpegService(
            (command, timeout, cancel) -> {
                lastCommand = command;
                if (command.get(0).equals("ffprobe")) {
                    return new ProcessResult(0, "{\"format\":{\"duration\":\"12.5\"}}");
                }
                return new ProcessResult(0, "");
            },
            new AppProperties());

    @Test
    void extractCommandShape() {
        List<String> cmd = service.extractAudioCommand(Path.of("/v/in.mp4"), Path.of("/v/out.m4a"));
        assertThat(cmd).startsWith("ffmpeg", "-y", "-i", Path.of("/v/in.mp4").toString())
                .contains("-vn", "-ac", "1", "-ar", "16000", "-c:a", "aac")
                .endsWith(Path.of("/v/out.m4a").toString());
    }

    @Test
    void probeParsesDurationJson() {
        double d = service.probeDurationSeconds(Path.of("/v/a.mp4"));
        assertThat(d).isEqualTo(12.5);
        assertThat(lastCommand.get(0)).isEqualTo("ffprobe");
        assertThat(lastCommand).contains("-print_format", "json", "-show_format");
    }

    @Test
    void probeFailsOnError() {
        FfmpegService failing = new FfmpegService(
                (command, timeout, cancel) -> new ProcessResult(1, "Invalid data"), new AppProperties());
        assertThatThrownBy(() -> failing.probeDurationSeconds(Path.of("/v/a.mp4")))
                .isInstanceOf(ProcessFailedException.class)
                .hasMessageContaining("ffprobe");
    }

    @Test
    void parsesBareJson() {
        assertThat(service.parseDurationSeconds("{\"format\":{\"duration\":\"3.75\"}}")).isEqualTo(3.75);
        assertThatThrownBy(() -> service.parseDurationSeconds("{\"format\":{}}"))
                .isInstanceOf(ProcessFailedException.class);
    }

    @Test
    void hasAudioStreamTrueWhenAudioTrackPresent() {
        FfmpegService withAudio = new FfmpegService(
                (command, timeout, cancel) -> {
                    lastCommand = command;
                    return new ProcessResult(0, "0\n");
                }, new AppProperties());
        assertThat(withAudio.hasAudioStream(Path.of("/v/a.mp4"))).isTrue();
        assertThat(lastCommand.get(0)).isEqualTo("ffprobe");
        assertThat(lastCommand).containsSubsequence(
                "-v", "error", "-select_streams", "a",
                "-show_entries", "stream=index", "-of", "csv=p=0");
        assertThat(lastCommand).endsWith(Path.of("/v/a.mp4").toString());
    }

    @Test
    void hasAudioStreamFalseWhenNoAudioTrack() {
        FfmpegService noAudio = new FfmpegService(
                (command, timeout, cancel) -> new ProcessResult(0, ""), new AppProperties());
        assertThat(noAudio.hasAudioStream(Path.of("/v/a.mp4"))).isFalse();
    }

    @Test
    void hasAudioStreamFailsOnError() {
        FfmpegService failing = new FfmpegService(
                (command, timeout, cancel) -> new ProcessResult(1, "Invalid data"), new AppProperties());
        assertThatThrownBy(() -> failing.hasAudioStream(Path.of("/v/a.mp4")))
                .isInstanceOf(ProcessFailedException.class)
                .hasMessageContaining("ffprobe");
    }

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

    @Test
    void directMediaUrlDetection() {
        assertThat(YtDlpService.isDirectMediaUrl("https://cdn.example.com/movie.mp4")).isTrue();
        assertThat(YtDlpService.isDirectMediaUrl("https://cdn.example.com/song.MP3")).isTrue();
        assertThat(YtDlpService.isDirectMediaUrl("https://www.bilibili.com/video/BV1xx")).isFalse();
        assertThat(YtDlpService.isDirectMediaUrl("https://example.com/watch?v=1")).isFalse();
    }
}
