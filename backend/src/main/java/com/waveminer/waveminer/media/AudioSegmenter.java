package com.waveminer.waveminer.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class AudioSegmenter {

    public record Chunk(double startSec, double endSec, Path file) {}

    private final FfmpegService ffmpeg;

    public AudioSegmenter(FfmpegService ffmpeg) { this.ffmpeg = ffmpeg; }

    public List<Chunk> split(Path input, Path workDir, long maxDurationSec, BooleanSupplier cancelSignal) {
        double duration = ffmpeg.probeDurationSeconds(input);
        List<double[]> ranges = cutPoints(duration, ffmpeg.detectSilences(input), maxDurationSec);
        List<Chunk> chunks = new ArrayList<>();
        try {
            int i = 1;
            for (double[] range : ranges) {
                Path out = workDir.resolve(String.format("chunk-%03d.m4a", i++));
                ffmpeg.cutAudio(input, out, range[0], range[1], cancelSignal);
                chunks.add(new Chunk(range[0], range[1], out));
            }
        } catch (RuntimeException e) {
            chunks.forEach(c -> {
                try {
                    Files.deleteIfExists(c.file());
                } catch (Exception ignored) {
                    // 清理失败不影响主流程
                }
            });
            throw e;
        }
        return chunks;
    }

    /**
     * 纯函数：返回切分区间 [start,end] 列表；无需切分返回空列表。
     * 贪心：窗口 [pos, pos+maxSec] 内取"静音中点 > pos+maxSec/2"的最大者；无则 pos+maxSec。
     */
    static List<double[]> cutPoints(double durationSec, List<FfmpegService.Silence> silences, double maxSec) {
        if (durationSec <= maxSec) return List.of();
        List<double[]> ranges = new ArrayList<>();
        double start = 0;
        while (durationSec - start > maxSec) {
            double windowEnd = start + maxSec;
            double best = -1;
            for (FfmpegService.Silence s : silences) {
                double mid = (s.startSec() + s.endSec()) / 2;
                if (mid > start + maxSec / 2 && mid <= windowEnd && mid > best) {
                    best = mid;
                }
            }
            double cut = best > 0 ? best : windowEnd;
            ranges.add(new double[]{start, cut});
            start = cut;
        }
        ranges.add(new double[]{start, durationSec});
        return ranges;
    }
}
