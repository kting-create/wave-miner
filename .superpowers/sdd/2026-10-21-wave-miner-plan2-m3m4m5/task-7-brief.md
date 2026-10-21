### Task 7: TaskEventPublisher + TaskRunner 事件埋点 + TranscribeStage + 产物白名单/MIME

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/pipeline/TaskEventPublisher.java`、`stage/TranscribeStage.java`
- Modify: `pipeline/TaskRunner.java`、`store/ArtifactStore.java`、`api/TaskController.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/stage/TranscribeStageTest.java`；`store/ArtifactStoreTest` 追加；`TaskRunnerTest` 构造器更新

**Interfaces:**
- Consumes: Task 2-6 全部、`TaskContext.engineId()/language()/diarize()`（Task 3）、`TaskCancelledException`（pipeline 包，无参构造）
- Produces:
  - `pipeline/TaskEventPublisher`（@Component）：
    - `record TaskEvent(String taskId, String type, String stage, String status, String message, Instant at)` + 静态工厂 `stage(taskId, stage, status, message)`（type="stage"）、`progress(taskId, stage, message)`（type="progress"）、`task(taskId, status)`（type="task"）
    - `interface Listener { void onEvent(TaskEvent e); }`；`addListener`/`removeListener`/`publish`（逐个通知，监听器异常吞掉记 warn 日志）
  - `TaskRunner` 构造器追加第 5 参数 `TaskEventPublisher events`：stage `RUNNING`/`DONE`/`FAILED`（含取消导致的 FAILED）后发布 `stage` 事件；任务终态（COMPLETED/FAILED/CANCELLED）发布 `task` 事件。`TaskRunnerTest` 更新构造并新增事件断言用例
  - `stage/TranscribeStage implements StageHandler`（@Component，type=TRANSCRIBE），构造 `(EngineRegistry registry, FfmpegService ffmpeg, AudioSegmenter segmenter, TaskEventPublisher events, AppProperties props)`：
    - 输入解析：`ctx.artifactOf(AUDIO_EXTRACT)` → `ctx.artifactOf(DOWNLOAD)` → `ctx.uploadedFile()`；皆无 → `IllegalStateException("转写缺少音频/视频输入（需要上游产物或上传文件）")`
    - 引擎：`registry.get(ctx.engineId())`；随后取消检查（`ctx.cancelRequested()` → `TaskCancelledException`）
    - 视频输入（`MediaTypes.isVideo`）→ `ffmpeg.extractAudio(input, dir/audio.m4a, ctx::cancelRequested)` 后以 `audio.m4a` 为输入并重探时长
    - 时长 > 引擎 `maxDurationSec` → 分片路径：`segmenter.split(input, dir/"chunks", maxDur, ctx::cancelRequested)`；逐片（进度 `转写分片 i/n`；每片前查取消 → TaskCancelledException）`engine.transcribe(chunk.file(), options, callback(ctx))`；raw 存 `engine_raw/{engineId}-chunk-XXX.json`；时间戳偏移 `chunk.startSec()*1000`；全部成功后 `SegmentMerger.merge(all, props.getMergeGapMs())` 并递归删除 `chunks/`；失败保留 chunks 目录
    - 时长 ≤ 上限 → 单次转写（进度 `正在调用转写引擎…`），raw 存 `engine_raw/{engineId}.json`
    - 引擎抛 `EngineCancelledException` → 转抛 `TaskCancelledException`；其他 `EngineException`：有 rawResponse 时先存档 `engine_raw/{engineId}.json` 再原样抛出
    - 成功：写 `transcript.json`（engine/engineId、language=ctx.language()、durationMs）/`transcript.srt`/`transcript.txt` → `ctx.putArtifact(TRANSCRIBE, "transcript.json")`；进度 `转写完成：N 段`
    - `private ProgressCallback callback(TaskContext ctx)`：update → `events.publish(progress(...))`；cancelled → `ctx.cancelRequested()`
  - `ArtifactStore.isAllowedArtifactName` 追加：精确 `transcript.json/transcript.srt/transcript.txt`；`engine_raw/<单层文件名>`（`rest.matches("[A-Za-z0-9._-]+")` 且不含 `..`）；`listArtifacts` 改 `Files.walk(dir, 2)` 并 `dir.relativize(p).toString().replace('\\','/')` 过滤（engine_raw 内文件以 `engine_raw/xxx.json` 形式列出）
  - `TaskController`：产物路由改 `@GetMapping("/{id}/artifacts/{name:.+}")`；MIME 表追加 `json→application/json`、`srt→application/x-subrip`、`txt→text/plain;charset=UTF-8`（`dir.resolve(name).normalize()` 与 `startsWith(dir)` 校验保留）

- [ ] **Step 1: ArtifactStore 测试追加（先失败）**

`store/ArtifactStoreTest` 追加：

```java
    @Test
    void transcriptAndEngineRawAllowed() throws Exception {
        ArtifactStore s = store();
        Path dir = s.taskDir("abc");
        Files.createDirectories(dir.resolve("engine_raw"));
        Files.writeString(dir.resolve("transcript.json"), "{}");
        Files.writeString(dir.resolve("transcript.srt"), "");
        Files.writeString(dir.resolve("transcript.txt"), "");
        Files.writeString(dir.resolve("engine_raw").resolve("minimax.json"), "{}");
        Files.writeString(dir.resolve("engine_raw").resolve("bad name!.json"), "{}");

        assertThat(s.isAllowedArtifactName("transcript.json")).isTrue();
        assertThat(s.isAllowedArtifactName("transcript.srt")).isTrue();
        assertThat(s.isAllowedArtifactName("transcript.txt")).isTrue();
        assertThat(s.isAllowedArtifactName("engine_raw/minimax.json")).isTrue();
        assertThat(s.isAllowedArtifactName("engine_raw/bad name!.json")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw/../../etc")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw/")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw")).isFalse();

        List<ArtifactInfo> artifacts = s.listArtifacts("abc");
        assertThat(artifacts).extracting(ArtifactInfo::name).contains(
                "transcript.json", "transcript.srt", "transcript.txt", "engine_raw/minimax.json");
    }
```

Run: `cd backend && ./mvnw -q test -Dtest=ArtifactStoreTest`
Expected: FAIL

- [ ] **Step 2: 写 TranscribeStage 测试（先失败）**

`stage/TranscribeStageTest.java`：

```java
package com.waveminer.waveminer.stage;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.engine.EngineException;
import com.waveminer.waveminer.engine.EngineInfo;
import com.waveminer.waveminer.engine.EngineRegistry;
import com.waveminer.waveminer.engine.Segment;
import com.waveminer.waveminer.engine.TranscribeOptions;
import com.waveminer.waveminer.engine.TranscribeResult;
import com.waveminer.waveminer.engine.TranscriptionEngine;
import com.waveminer.waveminer.media.AudioSegmenter;
import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.TaskCancelledException;
import com.waveminer.waveminer.pipeline.TaskContext;
import com.waveminer.waveminer.pipeline.TaskEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranscribeStageTest {

    @TempDir
    Path tmp;

    private FfmpegService ffmpeg;
    private AudioSegmenter segmenter;
    private TranscriptionEngine engine;
    private TranscribeStage stage;
    private TaskEventPublisher publisher;
    private final List<TaskEventPublisher.TaskEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setDataDir(tmp.toString());
        ffmpeg = mock(FfmpegService.class);
        segmenter = mock(AudioSegmenter.class);
        publisher = new TaskEventPublisher();
        publisher.addListener(events::add);
        engine = mock(TranscriptionEngine.class);
        when(engine.id()).thenReturn("fake");
        when(engine.info()).thenReturn(new EngineInfo("fake", "假引擎", 500, 50 * 1024 * 1024, true, List.of("zh")));

        @SuppressWarnings("unchecked")
        ObjectProvider<TranscriptionEngine> provider = mock(ObjectProvider.class);
        Mockito.when(provider.orderedStream()).thenReturn(java.util.stream.Stream.of(engine));
        EngineRegistry registry = new EngineRegistry(provider);

        stage = new TranscribeStage(registry, ffmpeg, segmenter, publisher, props);
    }

    private TaskContext ctx(String id, String url, Path uploaded) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(id));
        return new TaskContext(id, dir, url, uploaded, new AtomicBoolean(false), "fake", "zh", false);
    }

    private Path audio(String task, String name) throws Exception {
        Path p = tmp.resolve(task).resolve(name);
        Files.writeString(p, "audio");
        return p;
    }

    @Test
    void transcribesAudioInputAndWritesArtifacts() throws Exception {
        TaskContext c = ctx("t1", null, null);
        Path in = audio("t1", "audio.m4a");
        c.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(12.744);
        when(engine.transcribe(eq(in), eq(new TranscribeOptions("zh", false)), any()))
                .thenReturn(new TranscribeResult(
                        List.of(new Segment(100, 1660, "你好", 1)), "{\"segments\":[]}"));

        stage.run(c);

        assertThat(c.artifactOf(StageType.TRANSCRIBE)).hasFileName("transcript.json");
        Path dir = c.dir();
        assertThat(Files.readString(dir.resolve("transcript.json"))).contains("\"engine\":\"fake\"");
        assertThat(Files.readString(dir.resolve("transcript.srt")))
                .contains("00:00:00,100 --> 00:00:01,660");
        assertThat(Files.readString(dir.resolve("transcript.txt"))).contains("[00:00] 你好");
        assertThat(Files.readString(dir.resolve("engine_raw").resolve("fake.json"))).contains("segments");
        assertThat(events).anySatisfy(e -> assertThat(e.message()).contains("转写完成"));
    }

    @Test
    void videoInputExtractsAudioFirst() throws Exception {
        TaskContext c = ctx("t2", null, null);
        Path video = audio("t2", "source.mp4");
        c.putArtifact(StageType.DOWNLOAD, "source.mp4");
        Path outAudio = c.dir().resolve("audio.m4a");
        Mockito.doAnswer(inv -> {
            Files.writeString(outAudio, "audio");
            return null;
        }).when(ffmpeg).extractAudio(eq(video), eq(outAudio), any());
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(5.0);
        when(engine.transcribe(eq(outAudio), any(), any()))
                .thenReturn(new TranscribeResult(List.of(new Segment(0, 500, "hi", null)), "{}"));

        stage.run(c);

        Mockito.verify(ffmpeg).extractAudio(eq(video), eq(outAudio), any());
        assertThat(Files.exists(c.dir().resolve("transcript.json"))).isTrue();
    }

    @Test
    void longAudioSplitsOffsetsAndMerges() throws Exception {
        TaskContext c = ctx("t3", null, null);
        Path in = audio("t3", "audio.m4a");
        c.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(1200.0);
        Path chunk1 = c.dir().resolve("chunks").resolve("chunk-001.m4a");
        Path chunk2 = c.dir().resolve("chunks").resolve("chunk-002.m4a");
        Files.createDirectories(chunk1.getParent());
        Files.writeString(chunk1, "c1");
        Files.writeString(chunk2, "c2");
        when(segmenter.split(eq(in), any(Path.class), eq(500L), any())).thenReturn(List.of(
                new AudioSegmenter.Chunk(0, 492, chunk1),
                new AudioSegmenter.Chunk(492, 1200, chunk2)));
        when(engine.transcribe(eq(chunk1), any(), any())).thenReturn(new TranscribeResult(
                List.of(new Segment(0, 1000, "前半。", 1)), "{\"c\":1}"));
        when(engine.transcribe(eq(chunk2), any(), any())).thenReturn(new TranscribeResult(
                List.of(new Segment(0, 800, "后半。", 1)), "{\"c\":2}"));

        stage.run(c);

        String json = Files.readString(c.dir().resolve("transcript.json"));
        assertThat(json).contains("\"startMs\":492000"); // 第二片整体偏移 492s
        assertThat(Files.readString(c.dir().resolve("engine_raw").resolve("fake-chunk-001.json"))).contains("\"c\":1");
        assertThat(Files.exists(c.dir().resolve("chunks"))).isFalse(); // 成功后清理
    }

    @Test
    void engineFailureArchivesRawAndPropagates() throws Exception {
        TaskContext c = ctx("t4", null, null);
        Path in = audio("t4", "audio.m4a");
        c.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(10.0);
        when(engine.transcribe(eq(in), any(), any()))
                .thenThrow(new EngineException("引擎炸了", "{\"error\":1}"));

        assertThatThrownBy(() -> stage.run(c))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("引擎炸了");
        assertThat(Files.readString(c.dir().resolve("engine_raw").resolve("fake.json"))).contains("\"error\":1");
    }

    @Test
    void engineCancellationBecomesTaskCancelled() throws Exception {
        TaskContext c = ctx("t5", null, null);
        Path in = audio("t5", "audio.m4a");
        c.putArtifact(StageType.AUDIO_EXTRACT, "audio.m4a");
        when(ffmpeg.probeDurationSeconds(any())).thenReturn(10.0);
        when(engine.transcribe(eq(in), any(), any()))
                .thenThrow(new com.waveminer.waveminer.engine.EngineCancelledException("已取消"));

        assertThatThrownBy(() -> stage.run(c)).isInstanceOf(TaskCancelledException.class);
    }

    @Test
    void unknownEngineFails() throws Exception {
        TaskContext c = new TaskContext("t6", Files.createDirectories(tmp.resolve("t6")),
                null, null, new AtomicBoolean(false), "no-such", "auto", false);
        assertThatThrownBy(() -> stage.run(c))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("未知转写引擎");
    }

    @Test
    void missingInputFails() throws Exception {
        TaskContext c = ctx("t7", "https://e.com/a", null);
        assertThatThrownBy(() -> stage.run(c))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("输入");
    }

    @Test
    void cancelRequestedStopsBeforeEngine() throws Exception {
        Path uploaded = audio("t8", "source.mp3");
        TaskContext c = new TaskContext("t8", Files.createDirectories(tmp.resolve("t8")),
                null, uploaded, new AtomicBoolean(true), "fake", "auto", false);
        assertThatThrownBy(() -> stage.run(c)).isInstanceOf(TaskCancelledException.class);
        Mockito.verify(engine, Mockito.never()).transcribe(any(), any(), any());
    }
}
```

- [ ] **Step 3: 实现 TaskEventPublisher**

`pipeline/TaskEventPublisher.java`：

```java
package com.waveminer.waveminer.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskEventPublisher {

    public record TaskEvent(String taskId, String type, String stage, String status, String message, Instant at) {
        public static TaskEvent stage(String taskId, String stage, String status, String message) {
            return new TaskEvent(taskId, "stage", stage, status, message, Instant.now());
        }

        public static TaskEvent progress(String taskId, String stage, String message) {
            return new TaskEvent(taskId, "progress", stage, null, message, Instant.now());
        }

        public static TaskEvent task(String taskId, String status) {
            return new TaskEvent(taskId, "task", null, status, null, Instant.now());
        }
    }

    public interface Listener {
        void onEvent(TaskEvent e);
    }

    private static final Logger log = LoggerFactory.getLogger(TaskEventPublisher.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public void publish(TaskEvent e) {
        for (Listener l : listeners) {
            try {
                l.onEvent(e);
            } catch (RuntimeException ex) {
                log.warn("任务事件监听器异常: {}", ex.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: TaskRunner 埋点 + 构造器/测试更新**

`TaskRunner` 构造器改为 `(AppProperties props, List<StageHandler> handlers, TaskRepository repo, ArtifactStore store, TaskEventPublisher events)`。发布点：

```java
            repo.updateStage(taskId, type, StageStatus.RUNNING, null, null);
            events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "RUNNING", null));
            try {
                handler.run(ctx);
            } catch (TaskCancelledException e) {
                repo.updateStage(taskId, type, StageStatus.FAILED, "已取消", null);
                events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "FAILED", "已取消"));
                repo.updateTaskStatus(taskId, TaskStatus.CANCELLED, null);
                events.publish(TaskEventPublisher.TaskEvent.task(taskId, "CANCELLED"));
                repo.updateFinishedAt(taskId, Instant.now());
                return;
            } catch (Exception e) {
                fail(taskId, type, e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }
            repo.updateStage(taskId, type, StageStatus.DONE, null, ctx.artifactNameOf(type));
            events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "DONE", null));
```

`fail(...)` 中（updateStage 之后）补 `events.publish(TaskEventPublisher.TaskEvent.stage(taskId, type.name(), "FAILED", message))`；任务终态 `updateTaskStatus` 三处（COMPLETED / fail 内的 FAILED / cancel 路径的 CANCELLED，含 execute 开头的取消短路）各补一条 `events.publish(TaskEventPublisher.TaskEvent.task(taskId, "<终态名>"))`。

`TaskRunnerTest`：setUp 中创建 `TaskEventPublisher publisher = new TaskEventPublisher();` 并传入构造器；既有 4 个用例不动；追加：

```java
    @Test
    void publishesStageAndTaskEvents() {
        java.util.List<TaskEventPublisher.TaskEvent> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        publisher.addListener(seen::add);
        insertTask("ev");
        runner.submit(repo.find("ev").orElseThrow());
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                seen.stream().anyMatch(e -> e.type().equals("task")));
        assertThat(seen).anySatisfy(e -> assertThat(e.type()).isEqualTo("stage"));
        assertThat(seen).anySatisfy(e -> assertThat(e.status()).isEqualTo("COMPLETED"));
    }
```

- [ ] **Step 5: 实现 TranscribeStage**

`stage/TranscribeStage.java`：

```java
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
        Path input = resolveInput(ctx);
        TranscriptionEngine engine = registry.get(ctx.engineId()); // 未知引擎 → EngineException
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
```

- [ ] **Step 6: ArtifactStore / TaskController 修改**

`ArtifactStore.isAllowedArtifactName` 在末尾 `return false;` 前追加：

```java
        if (name.equals("transcript.json") || name.equals("transcript.srt") || name.equals("transcript.txt")) {
            return true;
        }
        if (name.startsWith("engine_raw/")) {
            String rest = name.substring("engine_raw/".length());
            return !rest.isBlank() && !rest.contains("/") && !rest.contains("..")
                    && rest.matches("[A-Za-z0-9._-]+");
        }
```

`ArtifactStore.listArtifacts` 改为：

```java
    public List<ArtifactInfo> listArtifacts(String taskId) {
        Path dir = taskDir(taskId);
        try (Stream<Path> files = Files.walk(dir, 2)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(this::isAllowedArtifactName)
                    .map(name -> new ArtifactInfo(name, sizeOf(dir.resolve(name))))
                    .sorted(Comparator.comparing(ArtifactInfo::name))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

（`replace('\\','/')` 处理 Windows 分隔符。）

`TaskController`：
- `@GetMapping("/{id}/artifacts/{name}")` → `@GetMapping("/{id}/artifacts/{name:.+}")`
- MIME 表追加三条：

```java
            Map.entry("json", MediaType.parseMediaType("application/json")),
            Map.entry("srt", MediaType.parseMediaType("application/x-subrip")),
            Map.entry("txt", MediaType.parseMediaType("text/plain;charset=UTF-8")));
```

- [ ] **Step 7: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add backend && git commit -m "feat: TRANSCRIBE 阶段、进度事件总线与转写产物白名单"
```

---

