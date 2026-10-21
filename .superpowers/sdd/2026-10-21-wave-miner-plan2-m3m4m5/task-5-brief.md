### Task 5: AppProperties 转写配置 + FfmpegService.detectSilences/cutAudio + AudioSegmenter

**Files:**
- Modify: `backend/src/main/java/com/waveminer/waveminer/config/AppProperties.java`、`media/FfmpegService.java`、`backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/waveminer/waveminer/media/AudioSegmenter.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/media/AudioSegmenterTest.java`（新建）；`FfmpegServiceTest` 追加

**Interfaces:**
- Consumes: `FfmpegService.Runner`（3 参含 cancelSignal）、`ProcessResult`
- Produces:
  - `AppProperties` 新增（含 getter/setter）：`minimaxBaseUrl="https://api.minimax.cn"`、`minimaxModel="asr-1.0"`、`aliyunBaseUrl="https://dashscope.aliyuncs.com/api/v1"`、`aliyunModel="qwen-audio-3.0-asr-flash-filetrans"`、`engineHttpTimeoutSeconds=600`（方法 `Duration engineHttpTimeout()`）、`engineRetryAttempts=3`、`transcribePollIntervalSeconds=5`、`transcribeTimeoutSeconds=7200`、`silenceThresholdDb=-35.0`（double）、`silenceMinDurationSec=0.6`（double）、`mergeGapMs=800`
  - `FfmpegService`：
    - `record Silence(double startSec, double endSec)`（定义在 FfmpegService 内部：`FfmpegService.Silence`）
    - `List<String> detectSilencesCommand(Path in)`：`{ffmpeg} -i {in} -af silencedetect=noise={threshold}dB:d={minDur} -f null -`
    - `List<Silence> detectSilences(Path in)`：先 `probeDurationSeconds`，再解析输出行 `silence_start: X` / `silence_end: Y | silence_duration: Z`；结尾未闭合的 silence_start 以音频时长收尾
    - `static double parseSilenceStart(String line)` / `static double parseSilenceEnd(String line)`（包级，供测试）
    - `List<String> cutAudioCommand(Path in, Path out, double startSec, double endSec)`：`{ffmpeg} -y -i {in} -ss {s} -to {e} -vn -ac 1 -ar 16000 -c:a aac {out}`
    - `void cutAudio(Path in, Path out, double startSec, double endSec, BooleanSupplier cancelSignal)`（非零退出 → ProcessFailedException 中文）
  - `AudioSegmenter`（@Component，构造参数 `FfmpegService`）：
    - `record Chunk(double startSec, double endSec, Path file)`
    - `List<Chunk> split(Path input, Path workDir, long maxDurationSec, BooleanSupplier cancelSignal)`：探测时长 → detectSilences → cutPoints → 逐片 `cutAudio` 到 `workDir/chunk-001.m4a`…；切分过程中失败则删除已生成片后原样抛出
    - `static List<double[]> cutPoints(double durationSec, List<FfmpegService.Silence> silences, double maxSec)`（包级，纯函数）：`durationSec <= maxSec` 返回空；否则贪心——窗口 `[pos, pos+maxSec]` 内取"静音中点 > pos+maxSec/2"的最大者，无则 `pos+maxSec`；产出区间列表，首区间 start=0，末区间 end=durationSec

- [ ] **Step 1: AppProperties/application.yml 扩展**

`AppProperties` 追加字段与 getter/setter（模式同现有）。`application.yml` 的 `app:` 下追加：

```yaml
  # —— 转写引擎（计划 2）——
  minimax-base-url: https://api.minimax.cn
  minimax-model: asr-1.0
  aliyun-base-url: https://dashscope.aliyuncs.com/api/v1
  aliyun-model: qwen-audio-3.0-asr-flash-filetrans
  engine-http-timeout-seconds: 600
  engine-retry-attempts: 3
  transcribe-poll-interval-seconds: 5
  transcribe-timeout-seconds: 7200
  silence-threshold-db: -35.0
  silence-min-duration-sec: 0.6
  merge-gap-ms: 800
```

- [ ] **Step 2: 写测试（先失败）**

`media/AudioSegmenterTest.java`：

```java
package com.waveminer.waveminer.media;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioSegmenterTest {

    private static FfmpegService.Silence sil(double s, double e) {
        return new FfmpegService.Silence(s, e);
    }

    @Test
    void shortAudioNeedsNoCut() {
        assertThat(AudioSegmenter.cutPoints(120, List.of(), 500)).isEmpty();
    }

    @Test
    void cutsAtSilenceMidpoints() {
        List<double[]> cuts = AudioSegmenter.cutPoints(900, List.of(sil(490, 494)), 500);
        assertThat(cuts).hasSize(2);
        assertThat(cuts.get(0)).containsExactly(0.0, 492.0);
        assertThat(cuts.get(1)).containsExactly(492.0, 900.0);
    }

    @Test
    void multipleCutsAtSilenceMidpoints() {
        List<double[]> cuts = AudioSegmenter.cutPoints(1200, List.of(sil(490, 494), sil(980, 984)), 500);
        assertThat(cuts).hasSize(3);
        assertThat(cuts.get(0)).containsExactly(0.0, 492.0);
        assertThat(cuts.get(1)).containsExactly(492.0, 982.0);
        assertThat(cuts.get(2)).containsExactly(982.0, 1200.0);
    }

    @Test
    void noSilenceForcesHardCut() {
        List<double[]> cuts = AudioSegmenter.cutPoints(1200, List.of(), 500);
        assertThat(cuts).hasSize(3);
        assertThat(cuts.get(0)).containsExactly(0.0, 500.0);
        assertThat(cuts.get(1)).containsExactly(500.0, 1000.0);
        assertThat(cuts.get(2)).containsExactly(1000.0, 1200.0);
    }

    @Test
    void silenceNearWindowEndPreferred() {
        List<double[]> cuts = AudioSegmenter.cutPoints(900, List.of(sil(298, 302), sil(478, 482)), 500);
        assertThat(cuts).hasSize(2);
        assertThat(cuts.get(0)).containsExactly(0.0, 480.0);
        assertThat(cuts.get(1)).containsExactly(480.0, 900.0);
    }

    @Test
    void parseSilenceLines() {
        assertThat(FfmpegService.parseSilenceStart("[silencedetect @ 0x0] silence_start: 12.3456")).isEqualTo(12.3456);
        assertThat(FfmpegService.parseSilenceEnd(
                "[silencedetect @ 0x0] silence_end: 13.5 | silence_duration: 1.154")).isEqualTo(13.5);
    }
}
```

`FfmpegServiceTest` 追加（沿用现有 lastCommand 桩模式）：

```java
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
```

Run: `cd backend && ./mvnw -q test`
Expected: FAIL，编译错误

- [ ] **Step 3: 实现 FfmpegService 扩展**

`FfmpegService` 追加（内部 record Silence 放类顶部区域）：

```java
    public record Silence(double startSec, double endSec) {}

    List<String> detectSilencesCommand(Path in) {
        return List.of(props.getFfmpegPath(), "-i", in.toString(),
                "-af", "silencedetect=noise=" + props.getSilenceThresholdDb() + "dB:d="
                        + props.getSilenceMinDurationSec(),
                "-f", "null", "-");
    }

    static double parseSilenceStart(String line) {
        int idx = line.indexOf("silence_start:");
        if (idx < 0) throw new IllegalArgumentException("非 silencedetect 输出行: " + line);
        return Double.parseDouble(line.substring(idx + "silence_start:".length()).trim());
    }

    static double parseSilenceEnd(String line) {
        int idx = line.indexOf("silence_end:");
        if (idx < 0) throw new IllegalArgumentException("非 silencedetect 输出行: " + line);
        return Double.parseDouble(line.substring(idx + "silence_end:".length()).trim());
    }

    public List<Silence> detectSilences(Path in) {
        double duration = probeDurationSeconds(in);
        ProcessResult r = runner.run(detectSilencesCommand(in), props.processTimeout());
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("ffmpeg 静音检测失败:\n" + tail(r.output()));
        }
        List<Silence> out = new ArrayList<>();
        Double openStart = null;
        for (String line : r.output().split("\n")) {
            if (line.contains("silence_start:")) {
                openStart = parseSilenceStart(line);
            } else if (line.contains("silence_end:") && openStart != null) {
                out.add(new Silence(openStart, parseSilenceEnd(line)));
                openStart = null;
            }
        }
        if (openStart != null) {
            out.add(new Silence(openStart, duration));
        }
        return out;
    }

    List<String> cutAudioCommand(Path in, Path out, double startSec, double endSec) {
        return List.of(props.getFfmpegPath(), "-y", "-i", in.toString(),
                "-ss", trimNum(startSec), "-to", trimNum(endSec),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "aac", out.toString());
    }

    public void cutAudio(Path in, Path out, double startSec, double endSec, BooleanSupplier cancelSignal) {
        ProcessResult r = runner.run(cutAudioCommand(in, out, startSec, endSec), props.processTimeout(), cancelSignal);
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("ffmpeg 切分音频失败:\n" + tail(r.output()));
        }
    }

    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
```

（import 追加 `java.util.ArrayList`、`java.util.function.BooleanSupplier`——`List` 已有。）

- [ ] **Step 4: 实现 AudioSegmenter**

`media/AudioSegmenter.java`：

```java
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
```

- [ ] **Step 5: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend && git commit -m "feat: 静音检测与超长音频切分器"
```

---

