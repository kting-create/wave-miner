package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.media.ProcessFailedException;
import com.waveminer.waveminer.pipeline.StageHandler;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskCancelledException;
import com.waveminer.waveminer.pipeline.TaskContext;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class AudioExtractStage implements StageHandler {

    private final FfmpegService ffmpeg;

    public AudioExtractStage(FfmpegService ffmpeg) { this.ffmpeg = ffmpeg; }

    @Override
    public StageType type() { return StageType.AUDIO_EXTRACT; }

    @Override
    public void run(TaskContext ctx) {
        Path input = ctx.artifactOf(StageType.DOWNLOAD);
        if (input == null) {
            input = ctx.uploadedFile().orElse(null);
        }
        if (input == null) {
            throw new IllegalStateException("提取音频缺少视频输入（需要下载产物或上传视频文件）");
        }
        if (!ffmpeg.hasAudioStream(input)) {
            throw new IllegalStateException("该视频不包含音轨，无法提取音频");
        }
        Path out = ctx.dir().resolve("audio.m4a");
        try {
            ffmpeg.extractAudio(input, out, ctx::cancelRequested);
        } catch (ProcessFailedException e) {
            if (ctx.cancelRequested()) {
                throw new TaskCancelledException();
            }
            throw e;
        }
        if (ctx.cancelRequested()) {
            throw new TaskCancelledException();
        }
        ctx.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
    }
}
