package com.waveminer.waveminer.pipeline;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.media.MediaTypes;
import com.waveminer.waveminer.store.ArtifactStore;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PipelineService {

    public record CreateTaskCommand(String url, MultipartFile file, List<StageType> stages, String language) {}

    public interface Runner {
        void submit(Task task);
    }

    private final TaskRepository repo;
    private final ArtifactStore artifacts;
    private final AppProperties props;
    private final Runner runner;

    LongPredicate diskSpaceCheck;

    public PipelineService(TaskRepository repo, ArtifactStore artifacts,
                           AppProperties props, Runner runner) {
        this.repo = repo;
        this.artifacts = artifacts;
        this.props = props;
        this.runner = Objects.requireNonNull(runner, "TaskRunner 未注入");
        this.diskSpaceCheck = required -> {
            File dir = new File(props.getDataDir()).getAbsoluteFile();
            while (!dir.exists() && dir.getParentFile() != null) {
                dir = dir.getParentFile();
            }
            return dir.getUsableSpace() > required;
        };
    }

    void setDiskSpaceCheck(LongPredicate check) {
        this.diskSpaceCheck = check;
    }

    public Task create(CreateTaskCommand cmd) {
        validate(cmd);
        boolean isUrl = cmd.url() != null && !cmd.url().isBlank();

        Task t = new Task();
        t.setId(UUID.randomUUID().toString());
        t.setSourceType(isUrl ? SourceType.URL : SourceType.UPLOAD);
        t.setSourceRef(isUrl ? cmd.url().trim() : cmd.file().getOriginalFilename());
        t.setTitle(isUrl ? titleFromUrl(cmd.url()) : cmd.file().getOriginalFilename());
        t.setStages(List.copyOf(new LinkedHashSet<>(cmd.stages())));
        t.setStatus(TaskStatus.PENDING);
        t.setCreatedAt(Instant.now());

        if (!isUrl) {
            if (!diskSpaceCheck.test(cmd.file().getSize() * 3 / 2)) {
                throw new IllegalArgumentException("磁盘剩余空间不足，无法保存上传文件");
            }
            saveUpload(t.getId(), cmd.file());
        }

        repo.insert(t);
        repo.insertStages(t.getId(), t.getStages());
        runner.submit(t);
        return t;
    }

    private void validate(CreateTaskCommand cmd) {
        List<StageType> stages = cmd.stages();
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个功能（下载/提取音频/转写）");
        }
        Set<StageType> distinct = new LinkedHashSet<>(stages);
        if (distinct.size() != stages.size()) {
            throw new IllegalArgumentException("功能选择存在重复");
        }
        boolean hasUrl = cmd.url() != null && !cmd.url().isBlank();
        boolean hasFile = cmd.file() != null && !cmd.file().isEmpty();
        boolean hasDownload = stages.contains(StageType.DOWNLOAD);

        if (hasUrl && hasFile) {
            throw new IllegalArgumentException("视频链接和文件上传只能选择一种输入方式");
        }
        if (!hasUrl && !hasFile) {
            throw new IllegalArgumentException("请提供视频链接或上传文件");
        }
        if (hasUrl) {
            if (!hasDownload) {
                throw new IllegalArgumentException("使用链接输入时必须勾选\"下载\"功能");
            }
            String u = cmd.url().trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                throw new IllegalArgumentException("仅支持 http(s) 链接");
            }
            try {
                URI.create(u);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("链接格式无效");
            }
        }
        if (hasFile) {
            if (hasDownload) {
                throw new IllegalArgumentException("上传文件时不能勾选\"下载\"功能");
            }
            String filename = cmd.file().getOriginalFilename();
            String ext = MediaTypes.extension(filename);
            if (!MediaTypes.ALL_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException("不支持的文件类型：" + (ext.isBlank() ? "（无扩展名）" : ext));
            }
            if (stages.contains(StageType.AUDIO_EXTRACT) && !MediaTypes.isVideo(filename)) {
                throw new IllegalArgumentException("提取音频需要视频文件（当前为 ." + ext + "）");
            }
        }
    }

    private void saveUpload(String taskId, MultipartFile file) {
        Path dir = artifacts.taskDir(taskId);
        String ext = MediaTypes.extension(file.getOriginalFilename());
        Path dest = dir.resolve("source." + ext);
        try (var in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("保存上传文件失败", e);
        }
    }

    private static String titleFromUrl(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            int slash = path.lastIndexOf('/');
            String last = slash >= 0 ? path.substring(slash + 1) : path;
            return last.isBlank() ? url.trim() : last;
        } catch (IllegalArgumentException e) {
            return url.trim();
        }
    }
}
