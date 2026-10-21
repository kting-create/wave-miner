package com.waveminer.waveminer.pipeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TaskRepository {

    private static final RowMapper<Task> TASK_MAPPER = TaskRepository::mapTask;

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Task insert(Task t) {
        jdbc.update("INSERT INTO task(id,source_type,source_ref,title,stages,status,error_message,created_at,started_at,finished_at,engine_id,language,diarize) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t.getId(), t.getSourceType().name(), t.getSourceRef(), t.getTitle(),
                joinStages(t.getStages()), t.getStatus().name(), t.getErrorMessage(),
                ts(t.getCreatedAt()), ts(t.getStartedAt()), ts(t.getFinishedAt()),
                t.getEngineId(), t.getLanguage(), t.isDiarize() ? 1 : 0);
        return t;
    }

    @Transactional
    public void insertStages(String taskId, List<StageType> stages) {
        for (StageType s : stages) {
            jdbc.update("INSERT INTO task_stage(task_id,stage_type,status) VALUES(?,?,'PENDING')",
                    taskId, s.name());
        }
    }

    public Optional<Task> find(String id) {
        List<Task> rows = jdbc.query("SELECT * FROM task WHERE id = ?", TASK_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<TaskStage> stagesOf(String taskId) {
        return jdbc.query("SELECT * FROM task_stage WHERE task_id = ? ORDER BY rowid",
                TaskRepository::mapStage, taskId);
    }

    public List<Task> list(String status, int page, int size) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM task ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?",
                    TASK_MAPPER, size, page * size);
        }
        return jdbc.query("SELECT * FROM task WHERE status = ? ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?",
                TASK_MAPPER, status, size, page * size);
    }

    public long count(String status) {
        if (status == null || status.isBlank()) {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM task", Long.class);
            return n == null ? 0 : n;
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    @Transactional
    public void updateTaskStatus(String id, TaskStatus status, String errorMessage) {
        jdbc.update("UPDATE task SET status = ?, error_message = ? WHERE id = ?",
                status.name(), errorMessage, id);
    }

    @Transactional
    public void updateStartedAt(String id, Instant t) {
        jdbc.update("UPDATE task SET started_at = ? WHERE id = ?", ts(t), id);
    }

    @Transactional
    public void updateFinishedAt(String id, Instant t) {
        jdbc.update("UPDATE task SET finished_at = ? WHERE id = ?", ts(t), id);
    }

    @Transactional
    public void updateStage(String taskId, StageType type, StageStatus status, String detail, String artifactPath) {
        jdbc.update("UPDATE task_stage SET status = ?, detail = ?, artifact_path = ? WHERE task_id = ? AND stage_type = ?",
                status.name(), detail, artifactPath, taskId, type.name());
    }

    @Transactional
    public void resetTaskForRetry(String id) {
        jdbc.update("UPDATE task SET status = 'PENDING', error_message = NULL, started_at = NULL, finished_at = NULL WHERE id = ?", id);
    }

    @Transactional
    public void resetStagesForRetry(String id) {
        jdbc.update("UPDATE task_stage SET status = 'PENDING', detail = NULL WHERE task_id = ? AND status != 'DONE'", id);
    }

    @Transactional
    public void delete(String id) {
        jdbc.update("DELETE FROM task_stage WHERE task_id = ?", id);
        jdbc.update("DELETE FROM task WHERE id = ?", id);
    }

    @Transactional
    public void markStaleRunningAsFailed(String message) {
        jdbc.update("UPDATE task SET status = 'FAILED', error_message = ?, finished_at = ? WHERE status IN ('PENDING','RUNNING')",
                message, ts(Instant.now()));
        jdbc.update("UPDATE task_stage SET status = 'FAILED', detail = ? WHERE status IN ('PENDING','RUNNING')", message);
    }

    private static Task mapTask(ResultSet rs, int i) throws SQLException {
        Task t = new Task();
        t.setId(rs.getString("id"));
        t.setSourceType(SourceType.valueOf(rs.getString("source_type")));
        t.setSourceRef(rs.getString("source_ref"));
        t.setTitle(rs.getString("title"));
        t.setStages(Arrays.stream(rs.getString("stages").split(","))
                .filter(s -> !s.isBlank())
                .map(StageType::valueOf)
                .collect(Collectors.toList()));
        t.setStatus(TaskStatus.valueOf(rs.getString("status")));
        t.setErrorMessage(rs.getString("error_message"));
        t.setCreatedAt(parseTs(rs.getString("created_at")));
        t.setStartedAt(parseTs(rs.getString("started_at")));
        t.setFinishedAt(parseTs(rs.getString("finished_at")));
        t.setEngineId(rs.getString("engine_id"));
        t.setLanguage(rs.getString("language"));
        t.setDiarize(rs.getInt("diarize") != 0);
        return t;
    }

    private static TaskStage mapStage(ResultSet rs, int i) throws SQLException {
        TaskStage s = new TaskStage();
        s.setTaskId(rs.getString("task_id"));
        s.setStageType(StageType.valueOf(rs.getString("stage_type")));
        s.setStatus(StageStatus.valueOf(rs.getString("status")));
        s.setDetail(rs.getString("detail"));
        s.setArtifactPath(rs.getString("artifact_path"));
        return s;
    }

    private static String joinStages(List<StageType> stages) {
        return stages.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static String ts(Instant t) { return t == null ? null : t.toString(); }

    private static Instant parseTs(String s) { return s == null ? null : Instant.parse(s); }
}
