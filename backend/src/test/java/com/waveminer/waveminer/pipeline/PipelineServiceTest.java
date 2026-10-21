package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.pipeline.PipelineService.CreateTaskCommand;
import com.waveminer.waveminer.store.ArtifactStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineServiceTest {

    @TempDir
    Path tmp;

    private StubRunner runner;
    private PipelineService service;

    static class StubRunner implements PipelineService.Runner {
        final List<Task> submitted = new ArrayList<>();
        @Override public void submit(Task t) { submitted.add(t); }
    }

    @BeforeEach
    void setUp() {
        var props = new com.waveminer.waveminer.config.AppProperties();
        props.setDataDir(tmp.resolve("data").toString());
        runner = new StubRunner();
        // 注意：不能用 jdbc:sqlite::memory:——DriverManagerDataSource 每次新建连接，
        // 内存库互不可见；用临时文件库保证跨连接可见
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("pipeline-test.db"));
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS task (id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_ref TEXT, title TEXT NOT NULL, stages TEXT NOT NULL, status TEXT NOT NULL, error_message TEXT, created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_stage (task_id TEXT NOT NULL, stage_type TEXT NOT NULL, status TEXT NOT NULL, detail TEXT, artifact_path TEXT, PRIMARY KEY (task_id, stage_type))");
        service = new PipelineService(new TaskRepository(jdbc), new ArtifactStore(props), props, runner);
    }

    private CreateTaskCommand url(String url, List<StageType> stages) {
        return new CreateTaskCommand(url, null, stages, null);
    }

    private CreateTaskCommand upload(String filename, List<StageType> stages) {
        return new CreateTaskCommand(null,
                new MockMultipartFile("file", filename, "application/octet-stream", new byte[]{1, 2, 3}),
                stages, null);
    }

    @Test
    void urlWithDownloadCreatesTaskAndSubmits() {
        Task t = service.create(url("https://example.com/video/BV1x", List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT)));
        assertThat(t.getId()).isNotBlank();
        assertThat(t.getSourceType()).isEqualTo(SourceType.URL);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(runner.submitted).hasSize(1);
    }

    @Test
    void uploadVideoWithAudioExtract() {
        Task t = service.create(upload("my.mp4", List.of(StageType.AUDIO_EXTRACT)));
        assertThat(t.getSourceType()).isEqualTo(SourceType.UPLOAD);
        assertThat(t.getTitle()).isEqualTo("my.mp4");
    }

    @Test
    void uploadRejectedWhenDiskSpaceInsufficient() {
        service.setDiskSpaceCheck(required -> false);
        assertThatThrownBy(() -> service.create(upload("my.mp4", List.of(StageType.AUDIO_EXTRACT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("磁盘剩余空间不足，无法保存上传文件");
        assertThat(runner.submitted).isEmpty();
    }

    @Test
    void urlRequiresDownloadStage() {
        assertThatThrownBy(() -> service.create(url("https://e.com/a.mp4", List.of(StageType.AUDIO_EXTRACT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("链接");
    }

    @Test
    void uploadMustNotContainDownload() {
        assertThatThrownBy(() -> service.create(upload("a.mp4", List.of(StageType.DOWNLOAD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上传");
    }

    @Test
    void audioExtractWithoutDownloadNeedsVideo() {
        assertThatThrownBy(() -> service.create(upload("song.mp3", List.of(StageType.AUDIO_EXTRACT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频");
    }

    @Test
    void unsupportedExtensionRejected() {
        assertThatThrownBy(() -> service.create(upload("doc.pdf", List.of(StageType.AUDIO_EXTRACT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件类型");
    }

    @Test
    void emptyStagesRejected() {
        assertThatThrownBy(() -> service.create(url("https://e.com/a", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void badUrlRejected() {
        assertThatThrownBy(() -> service.create(url("ftp://e.com/a", List.of(StageType.DOWNLOAD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    @Test
    void urlAndFileTogetherRejected() {
        assertThatThrownBy(() -> service.create(new CreateTaskCommand(
                "https://e.com/a",
                new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[]{1}),
                List.of(StageType.DOWNLOAD), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
