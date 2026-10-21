package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.media.ProcessFailedException;
import com.waveminer.waveminer.media.YtDlpService;
import com.waveminer.waveminer.pipeline.StageHandler;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskContext;
import com.waveminer.waveminer.store.ArtifactStore;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class DownloadStage implements StageHandler {

    private final YtDlpService ytDlp;

    public DownloadStage(YtDlpService ytDlp) { this.ytDlp = ytDlp; }

    @Override
    public StageType type() { return StageType.DOWNLOAD; }

    @Override
    public void run(TaskContext ctx) {
        String url = ctx.sourceUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("下载功能需要视频链接");
        }
        try {
            ytDlp.download(url, ctx.dir());
        } catch (ProcessFailedException e) {
            if (!YtDlpService.isDirectMediaUrl(url)) {
                throw e;
            }
            ytDlp.directDownload(url, ctx.dir());
        }
        Path source = ArtifactStore.findSourceFile(ctx.dir())
                .orElseThrow(() -> new IllegalStateException("下载完成但未找到产物文件"));
        ctx.putArtifact(StageType.DOWNLOAD, source.getFileName().toString());
    }
}
