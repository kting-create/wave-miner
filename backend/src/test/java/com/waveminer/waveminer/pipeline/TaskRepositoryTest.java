package com.waveminer.waveminer.pipeline;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TaskRepositoryTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
    }

    @Autowired TaskRepository repo;

    private Task newTask(String id) {
        Task t = new Task();
        t.setId(id);
        t.setSourceType(SourceType.URL);
        t.setSourceRef("https://example.com/a.mp4");
        t.setTitle("a.mp4");
        t.setStages(List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT));
        t.setStatus(TaskStatus.PENDING);
        t.setCreatedAt(Instant.parse("2026-10-21T00:00:00Z"));
        return t;
    }

    @Test
    void insertAndFind() {
        repo.insert(newTask("t1"));
        repo.insertStages("t1", List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT));

        Optional<Task> found = repo.find("t1");
        assertThat(found).isPresent();
        Task t = found.get();
        assertThat(t.getSourceType()).isEqualTo(SourceType.URL);
        assertThat(t.getStages()).containsExactly(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT);
        assertThat(t.getCreatedAt()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));

        List<TaskStage> stages = repo.stagesOf("t1");
        assertThat(stages).hasSize(2);
        assertThat(stages.get(0).getStageType()).isEqualTo(StageType.DOWNLOAD);
        assertThat(stages.get(0).getStatus()).isEqualTo(StageStatus.PENDING);
    }

    @Test
    void updateStatusAndStages() {
        repo.insert(newTask("t2"));
        repo.insertStages("t2", List.of(StageType.DOWNLOAD));

        repo.updateTaskStatus("t2", TaskStatus.RUNNING, null);
        repo.updateStartedAt("t2", Instant.now());
        repo.updateStage("t2", StageType.DOWNLOAD, StageStatus.DONE, null, "source.mp4");

        assertThat(repo.find("t2").orElseThrow().getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(repo.stagesOf("t2").get(0).getStatus()).isEqualTo(StageStatus.DONE);
        assertThat(repo.stagesOf("t2").get(0).getArtifactPath()).isEqualTo("source.mp4");

        repo.updateTaskStatus("t2", TaskStatus.FAILED, "下载失败");
        assertThat(repo.find("t2").orElseThrow().getErrorMessage()).isEqualTo("下载失败");
    }

    @Test
    void listPagingAndFilter() {
        repo.insert(newTask("t3a"));
        repo.insert(newTask("t3b"));
        repo.updateTaskStatus("t3b", TaskStatus.COMPLETED, null);

        // 同类其他测试会插入数据，这里只做非绝对数量断言
        assertThat(repo.list(null, 0, 10)).extracting(Task::getId).contains("t3a", "t3b");
        assertThat(repo.list("COMPLETED", 0, 10)).extracting(Task::getId).contains("t3b");
        assertThat(repo.list("COMPLETED", 0, 10)).extracting(Task::getId).doesNotContain("t3a");
        assertThat(repo.count(null)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void retryResetAndDelete() {
        repo.insert(newTask("t4"));
        repo.insertStages("t4", List.of(StageType.DOWNLOAD, StageType.AUDIO_EXTRACT));
        repo.updateStage("t4", StageType.DOWNLOAD, StageStatus.DONE, null, "source.mp4");
        repo.updateStage("t4", StageType.AUDIO_EXTRACT, StageStatus.FAILED, "boom", null);
        repo.updateTaskStatus("t4", TaskStatus.FAILED, "boom");

        repo.resetTaskForRetry("t4");
        repo.resetStagesForRetry("t4");

        Task t = repo.find("t4").orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.getErrorMessage()).isNull();
        assertThat(repo.stagesOf("t4").get(0).getStatus()).isEqualTo(StageStatus.DONE);
        assertThat(repo.stagesOf("t4").get(1).getStatus()).isEqualTo(StageStatus.PENDING);

        repo.delete("t4");
        assertThat(repo.find("t4")).isEmpty();
        assertThat(repo.stagesOf("t4")).isEmpty();
    }

    @Test
    void staleRecovery() {
        repo.insert(newTask("t5"));
        repo.insertStages("t5", List.of(StageType.DOWNLOAD));
        repo.updateTaskStatus("t5", TaskStatus.RUNNING, null);
        repo.updateStage("t5", StageType.DOWNLOAD, StageStatus.RUNNING, null, null);

        repo.markStaleRunningAsFailed("应用重启导致中断，可重试");

        Task t = repo.find("t5").orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(t.getErrorMessage()).contains("应用重启");
        assertThat(repo.stagesOf("t5").get(0).getStatus()).isEqualTo(StageStatus.FAILED);
    }

    @Test
    void engineFieldsRoundTrip() {
        Task t = newTask("t6e");
        t.setEngineId("minimax");
        t.setLanguage("zh");
        t.setDiarize(true);
        repo.insert(t);
        repo.insertStages("t6e", t.getStages());

        Task found = repo.find("t6e").orElseThrow();
        assertThat(found.getEngineId()).isEqualTo("minimax");
        assertThat(found.getLanguage()).isEqualTo("zh");
        assertThat(found.isDiarize()).isTrue();
    }

    @Test
    void findMissing() {
        assertThat(repo.find("nope")).isEmpty();
    }
}
