package com.waveminer.waveminer.store;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.media.MediaTypes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class ArtifactStore {

    private final Path root;

    public ArtifactStore(AppProperties props) {
        this.root = Path.of(props.getDataDir()).toAbsolutePath().normalize();
    }

    public Path taskDir(String taskId) {
        try {
            return Files.createDirectories(root.resolve(taskId));
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建任务目录: " + taskId, e);
        }
    }

    public static Optional<Path> findSourceFile(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("source."))
                    .filter(p -> MediaTypes.isMedia(p.getFileName().toString()))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean isAllowedArtifactName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        if (name.equals("audio.m4a")) return true;
        if (name.startsWith("source.")) {
            String ext = name.substring("source.".length());
            return !ext.isBlank() && MediaTypes.ALL_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    public List<ArtifactInfo> listArtifacts(String taskId) {
        Path dir = taskDir(taskId);
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(this::isAllowedArtifactName)
                    .map(name -> new ArtifactInfo(name, sizeOf(dir.resolve(name))))
                    .sorted(Comparator.comparing(ArtifactInfo::name))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
