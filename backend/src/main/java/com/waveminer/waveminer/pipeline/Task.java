package com.waveminer.waveminer.pipeline;

import java.time.Instant;
import java.util.List;

public class Task {
    private String id;
    private SourceType sourceType;
    private String sourceRef;
    private String title;
    private List<StageType> stages;
    private TaskStatus status;
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private String engineId;
    private String language;
    private boolean diarize;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<StageType> getStages() { return stages; }
    public void setStages(List<StageType> stages) { this.stages = stages; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getEngineId() { return engineId; }
    public void setEngineId(String v) { this.engineId = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { this.language = v; }
    public boolean isDiarize() { return diarize; }
    public void setDiarize(boolean v) { this.diarize = v; }
}
