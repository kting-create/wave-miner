package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AudioExtractStageTest {

    @TempDir
    Path tmp;

    private TaskContext ctx(String id, String url, Path uploaded) throws Exception {
        return new TaskContext(id, Files.createDirectories(tmp.resolve(id)), url, uploaded, new AtomicBoolean(false));
    }

    @Test
    void extractsFromDownloadArtifact() throws Exception {
        TaskContext c = ctx("t1", null, null);
        Path video = c.dir().resolve("source.mp4");
        Files.writeString(video, "v");
        c.putArtifact(StageType.DOWNLOAD, "source.mp4");
        FfmpegService ffmpeg = mock(FfmpegService.class);
        doAnswer(inv -> {
            Files.writeString(c.dir().resolve("audio.m4a"), "a");
            return null;
        }).when(ffmpeg).extractAudio(eq(video), eq(c.dir().resolve("audio.m4a")));

        new AudioExtractStage(ffmpeg).run(c);

        assertThat(c.artifactOf(StageType.AUDIO_EXTRACT)).hasFileName("audio.m4a");
    }

    @Test
    void extractsFromUploadedFile() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("t2"));
        Path uploaded = dir.resolve("source.mkv");
        Files.writeString(uploaded, "v");
        TaskContext c = new TaskContext("t2", dir, null, uploaded, new AtomicBoolean(false));
        FfmpegService ffmpeg = mock(FfmpegService.class);
        doAnswer(inv -> {
            Files.writeString(dir.resolve("audio.m4a"), "a");
            return null;
        }).when(ffmpeg).extractAudio(any(), any());

        new AudioExtractStage(ffmpeg).run(c);

        assertThat(c.artifactOf(StageType.AUDIO_EXTRACT)).hasFileName("audio.m4a");
    }

    @Test
    void missingInputFails() throws Exception {
        TaskContext c = ctx("t3", "https://e.com/a", null);
        assertThatThrownBy(() -> new AudioExtractStage(mock(FfmpegService.class)).run(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("输入");
    }
}
