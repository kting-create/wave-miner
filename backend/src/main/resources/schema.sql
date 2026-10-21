CREATE TABLE IF NOT EXISTS task (
    id            TEXT PRIMARY KEY,
    source_type   TEXT NOT NULL,
    source_ref    TEXT,
    title         TEXT NOT NULL,
    stages        TEXT NOT NULL,
    status        TEXT NOT NULL,
    error_message TEXT,
    created_at    TEXT NOT NULL,
    started_at    TEXT,
    finished_at   TEXT
);
CREATE TABLE IF NOT EXISTS task_stage (
    task_id       TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    stage_type    TEXT NOT NULL,
    status        TEXT NOT NULL,
    detail        TEXT,
    artifact_path TEXT,
    PRIMARY KEY (task_id, stage_type)
);
