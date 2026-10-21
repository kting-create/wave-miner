package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.engine.EngineCancelledException;
import com.waveminer.waveminer.engine.EngineException;
import com.waveminer.waveminer.engine.EngineRegistry;
import com.waveminer.waveminer.engine.ProgressCallback;
import com.waveminer.waveminer.engine.Segment;
import com.waveminer.waveminer.engine.SegmentMerger;
import com.waveminer.waveminer.engine.TranscribeOptions;
import com.waveminer.waveminer.engine.TranscribeResult;
import com.waveminer.waveminer.engine.TranscriptFormat;
import com.waveminer.waveminer.engine.TranscriptionEngine;
import com.waveminer.waveminer.media.AudioSegmenter;
import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.media.MediaTypes;
import com.waveminer.waveminer.pipeline.StageHandler;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskCancelledException;
import com.waveminer.waveminer.pipeline.TaskContext;
import com.waveminer.waveminer.pipeline.TaskEventPublisher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TranscribeStage implements StageHandler {

    private final EngineRegistry registry;
    private final FfmpegService ffmpeg;
    private final AudioSegmenter segmenter;
    private final TaskEventPublisher events;
    private final AppProperties props;

    public TranscribeStage(EngineRegistry registry, FfmpegService ffmpeg,
                           AudioSegmenter segmenter, TaskEventPublisher events, AppProperties props) {
        this.registry = registry;
        this.ffmpeg = ffmpeg;
        this.segmenter = segmenter;
        this.events = events;
        this.props = props;
    }

    @Override
    public StageType type() { return StageType.TRANSCRIBE; }

    @Override
    public void run(TaskContext ctx) {
        TranscriptionEngine engine = registry.get(ctx.engineId());
        Path input = resolveInput(ctx);
        if (ctx.cancelRequested()) {
            throw new TaskCancelledException();
        }
        long durationMs = Math.round(ffmpeg.probeDurationSeconds(input) * 1000);
        if (MediaTypes.isVideo(input.getFileName().toString())) {
            Path audio = ctx.dir().resolve("audio.m4a");
            ffmpeg.extractAudio(input, audio, ctx::cancelRequested);
            input = audio;
            durationMs = Math.round(ffmpeg.probeDurationSeconds(input) * 1000);
        }

        long maxDur = engine.info().maxDurationSec();
        TranscribeOptions options = TranscribeOptions.of(ctx.language(), ctx.diarize());
        List<Segment> segments;
        try {
            if (durationMs > maxDur * 1000) {
                segments = transcribeInChunks(ctx, engine, input, options, maxDur);
            } else {
                events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE", "正在调用转写引擎…"));
                TranscribeResult r = engine.transcribe(input, options, callback(ctx));
                archiveRaw(ctx, engine.id() + ".json", r.rawResponse());
                segments = r.segments();
            }
        } catch (EngineCancelledException e) {
            throw new TaskCancelledException();
        } catch (EngineException e) {
            if (e.getRawResponse() != null) {
                archiveRaw(ctx, engine.id() + ".json", e.getRawResponse());
            }
            throw e;
        }

        writeArtifacts(ctx, engine.id(), durationMs, segments);
        events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE",
                "转写完成：" + segments.size() + " 段"));
    }

    private Path resolveInput(TaskContext ctx) {
        Path input = ctx.artifactOf(StageType.AUDIO_EXTRACT);
        if (input == null) input = ctx.artifactOf(StageType.DOWNLOAD);
        if (input == null) input = ctx.uploadedFile().orElse(null);
        if (input == null) {
            throw new IllegalStateException("转写缺少音频/视频输入（需要上游产物或上传文件）");
        }
        return input;
    }

    private List<Segment> transcribeInChunks(TaskContext ctx, TranscriptionEngine engine,
                                             Path input, TranscribeOptions options, long maxDur) {
        Path chunksDir = ctx.dir().resolve("chunks");
        events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE",
                "音频超过引擎时长上限（" + maxDur + " 秒），开始静音切分"));
        List<AudioSegmenter.Chunk> chunks = segmenter.split(input, chunksDir, maxDur, ctx::cancelRequested);
        List<Segment> all = new ArrayList<>();
        try {
            int i = 1;
            for (AudioSegmenter.Chunk chunk : chunks) {
                if (ctx.cancelRequested()) {
                    throw new TaskCancelledException();
                }
                events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE",
                        "转写分片 " + i + "/" + chunks.size()));
                TranscribeResult r = engine.transcribe(chunk.file(), options, callback(ctx));
                archiveRaw(ctx, engine.id() + "-chunk-%03d.json".formatted(i), r.rawResponse());
                long offsetMs = Math.round(chunk.startSec() * 1000);
                for (Segment s : r.segments()) {
                    all.add(new Segment(s.startMs() + offsetMs, s.endMs() + offsetMs, s.text(), s.speaker()));
                }
                i++;
            }
        } catch (RuntimeException e) {
            // 失败保留分片目录便于排查
            throw e;
        }
        deleteRecursively(chunksDir);
        return SegmentMerger.merge(all, props.getMergeGapMs());
    }

    private void writeArtifacts(TaskContext ctx, String engineId, long durationMs, List<Segment> segments) {
        write(ctx.dir().resolve("transcript.json"),
                TranscriptFormat.toJson(engineId, ctx.language(), durationMs, segments));
        write(ctx.dir().resolve("transcript.srt"), TranscriptFormat.toSrt(segments));
        write(ctx.dir().resolve("transcript.txt"), TranscriptFormat.toTxt(segments));
        ctx.putArtifact(StageType.TRANSCRIBE, "transcript.json");
    }

    private ProgressCallback callback(TaskContext ctx) {
        return new ProgressCallback() {
            @Override public void update(String message) {
                events.publish(TaskEventPublisher.TaskEvent.progress(ctx.taskId(), "TRANSCRIBE", message));
            }

            @Override public boolean cancelled() { return ctx.cancelRequested(); }
        };
    }

    private void archiveRaw(TaskContext ctx, String name, String raw) {
        if (raw == null) return;
        try {
            Path dir = Files.createDirectories(ctx.dir().resolve("engine_raw"));
            write(dir.resolve(name), raw);
        } catch (IOException e) {
            throw new UncheckedIOException("存档引擎原始响应失败", e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入产物失败: " + file.getFileName(), e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 清理失败不阻塞主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
