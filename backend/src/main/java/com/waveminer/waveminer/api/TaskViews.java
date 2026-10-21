package com.waveminer.waveminer.api;

import com.waveminer.waveminer.pipeline.StageStatus;
import com.waveminer.waveminer.pipeline.StageType;
import java.util.List;

public final class TaskViews {

    public record StageView(StageType type, StageStatus status, String detail, String artifactName) {}

    public record ArtifactView(String name, long sizeBytes) {}

    public record TaskView(String id, String title, String sourceType, String sourceRef,
                           String status, String errorMessage,
                           String engineId, String language, boolean diarize,
                           String createdAt, String startedAt, String finishedAt,
                           List<StageView> stages, List<ArtifactView> artifacts) {}

    public record TaskPage(List<TaskView> items, long total, int page, int size) {}

    private TaskViews() {}
}
