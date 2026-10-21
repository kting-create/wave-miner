package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.media.ProcessFailedException;
import com.waveminer.waveminer.media.YtDlpService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DownloadStageTest {

    @TempDir
    Path tmp;

    private TaskContext ctx(String id, String url) throws Exception {
        return new TaskContext(id, Files.createDirectories(tmp.resolve(id)), url, null, new AtomicBoolean(false));
    }

    @Test
    void downloadsViaYtDlpAndRecordsArtifact() throws Exception {
        TaskContext c = ctx("t1", "https://e.com/a");
        YtDlpService yt = mock(YtDlpService.class);
        doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.writeString(dir.resolve("source.mp4"), "v");
            return null;
        }).when(yt).download(eq("https://e.com/a"), any(), any());

        new DownloadStage(yt).run(c);

        verify(yt).download(eq("https://e.com/a"), any(), any());
        assertThat(c.artifactOf(StageType.DOWNLOAD)).hasFileName("source.mp4");
    }

    @Test
    void fallsBackToDirectDownload() throws Exception {
        TaskContext c = ctx("t2", "https://cdn.e.com/a.mp4");
        YtDlpService yt = mock(YtDlpService.class);
        doThrow(new ProcessFailedException("yt-dlp 失败")).when(yt).download(any(), any(), any());
        doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.writeString(dir.resolve("source.mp4"), "v");
            return null;
        }).when(yt).directDownload("https://cdn.e.com/a.mp4", c.dir());

        new DownloadStage(yt).run(c);

        verify(yt).directDownload("https://cdn.e.com/a.mp4", c.dir());
        assertThat(c.artifactOf(StageType.DOWNLOAD)).hasFileName("source.mp4");
    }

    @Test
    void directFallbackFailureStillFails() throws Exception {
        TaskContext c = ctx("t3", "https://cdn.e.com/a.mp4");
        YtDlpService yt = mock(YtDlpService.class);
        doThrow(new ProcessFailedException("yt-dlp 失败")).when(yt).download(any(), any(), any());
        doThrow(new ProcessFailedException("直链也失败")).when(yt).directDownload(any(), any());

        assertThatThrownBy(() -> new DownloadStage(yt).run(c))
                .isInstanceOf(ProcessFailedException.class)
                .hasMessageContaining("直链也失败");
    }

    @Test
    void nonDirectUrlYtDlpFailurePropagates() throws Exception {
        TaskContext c = ctx("t4", "https://www.bilibili.com/video/BV1x");
        YtDlpService yt = mock(YtDlpService.class);
        doThrow(new ProcessFailedException("平台下载失败")).when(yt).download(any(), any(), any());

        assertThatThrownBy(() -> new DownloadStage(yt).run(c))
                .isInstanceOf(ProcessFailedException.class)
                .hasMessageContaining("平台下载失败");
    }

    @Test
    void requiresSourceUrl() {
        YtDlpService yt = mock(YtDlpService.class);
        TaskContext c = new TaskContext("t5", tmp, null, null, new AtomicBoolean(false));
        assertThatThrownBy(() -> new DownloadStage(yt).run(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("链接");
    }

    @Test
    void noSourceFileAfterDownloadFails() throws Exception {
        TaskContext c = ctx("t6", "https://e.com/a");
        YtDlpService yt = mock(YtDlpService.class);
        doNothing().when(yt).download(any(), any(), any());

        assertThatThrownBy(() -> new DownloadStage(yt).run(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("产物");
    }
}
