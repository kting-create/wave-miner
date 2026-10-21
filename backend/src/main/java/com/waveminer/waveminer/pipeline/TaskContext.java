package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.store.ArtifactStore;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TaskContext {
    private final String taskId;
    private final Path dir;
    private final String sourceUrl;
    private final Path uploadedFile;
    private final AtomicBoolean cancel;
    private final Map<StageType, String> artifacts = new EnumMap<>(StageType.class);

    public TaskContext(String taskId, Path dir, String sourceUrl, Path uploadedFile, AtomicBoolean cancel) {
        this.taskId = taskId;
        this.dir = dir;
        this.sourceUrl = sourceUrl;
        this.uploadedFile = uploadedFile;
        this.cancel = cancel;
    }
    public String taskId() { return taskId; }

    public Path dir() { return dir; }

    public String sourceUrl() { return sourceUrl; }

    public Optional<Path> uploadedFile() { return Optional.ofNullable(uploadedFile); }

    public boolean cancelRequested() { return cancel.get(); }

    public Path artifactOf(StageType type) {
        String name = artifacts.get(type);
        return name == null ? null : dir.resolve(name);
    }

    public void putArtifact(StageType type, String fileName) {
        artifacts.put(type, fileName);
    }

    String artifactNameOf(StageType type) { return artifacts.get(type); }

    static Path findUploadedFile(Path dir) {
        return ArtifactStore.findSourceFile(dir).orElse(null);
    }
}
