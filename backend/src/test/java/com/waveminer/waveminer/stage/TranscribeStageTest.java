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
        Files.createDirectories(p.getParent());
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
