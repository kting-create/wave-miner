package com.waveminer.waveminer.api;

import com.waveminer.waveminer.media.MediaTypes;
import com.waveminer.waveminer.pipeline.PipelineService;
import com.waveminer.waveminer.pipeline.StageType;
import com.waveminer.waveminer.pipeline.Task;
import com.waveminer.waveminer.pipeline.TaskRepository;
import com.waveminer.waveminer.pipeline.TaskRunner;
import com.waveminer.waveminer.pipeline.TaskStatus;
import com.waveminer.waveminer.store.ArtifactNotFoundException;
import com.waveminer.waveminer.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)");

    /** 任务 ID 必须是严格 UUID 格式，防止路径变量拼进任务目录造成穿越 */
    private static final Pattern ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Map<String, MediaType> MEDIA_TYPES = Map.ofEntries(
            Map.entry("mp4", MediaType.parseMediaType("video/mp4")),
            Map.entry("m4v", MediaType.parseMediaType("video/x-m4v")),
            Map.entry("mkv", MediaType.parseMediaType("video/x-matroska")),
            Map.entry("webm", MediaType.parseMediaType("video/webm")),
            Map.entry("mov", MediaType.parseMediaType("video/quicktime")),
            Map.entry("flv", MediaType.parseMediaType("video/x-flv")),
            Map.entry("ts", MediaType.parseMediaType("video/mp2t")),
            Map.entry("avi", MediaType.parseMediaType("video/x-msvideo")),
            Map.entry("mp3", MediaType.parseMediaType("audio/mpeg")),
            Map.entry("m4a", MediaType.parseMediaType("audio/mp4")),
            Map.entry("wav", MediaType.parseMediaType("audio/wav")),
            Map.entry("aac", MediaType.parseMediaType("audio/aac")),
            Map.entry("flac", MediaType.parseMediaType("audio/flac")),
            Map.entry("opus", MediaType.parseMediaType("audio/opus")),
            Map.entry("ogg", MediaType.parseMediaType("audio/ogg")),
            Map.entry("aiff", MediaType.parseMediaType("audio/aiff")),
            Map.entry("json", MediaType.parseMediaType("application/json")),
            Map.entry("srt", MediaType.parseMediaType("application/x-subrip")),
            Map.entry("txt", MediaType.parseMediaType("text/plain;charset=UTF-8")));

    private final PipelineService pipeline;
    private final TaskRepository repo;
    private final ArtifactStore artifacts;
    private final TaskRunner runner;

    public TaskController(PipelineService pipeline, TaskRepository repo,
                          ArtifactStore artifacts, TaskRunner runner) {
        this.pipeline = pipeline;
        this.repo = repo;
        this.artifacts = artifacts;
        this.runner = runner;
    }

    @PostMapping
    public TaskViews.TaskView create(
            @RequestParam(required = false) String url,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam String stages,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String engineId,
            @RequestParam(defaultValue = "false") String diarize) {
        List<StageType> stageList = Arrays.stream(stages.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(TaskController::parseStage).toList();
        Task t = pipeline.create(new PipelineService.CreateTaskCommand(
                url, file, stageList, language, engineId, Boolean.parseBoolean(diarize)));
        return toView(t);
    }

    @GetMapping
    public TaskViews.TaskPage list(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        List<TaskViews.TaskView> items = repo.list(status, p, s).stream().map(this::toView).toList();
        return new TaskViews.TaskPage(items, repo.count(status), p, s);
    }

    @GetMapping("/{id}")
    public TaskViews.TaskView detail(@PathVariable String id) {
        requireValidId(id);
        return toView(find(id));
    }

    @PostMapping("/{id}/retry")
    public TaskViews.TaskView retry(@PathVariable String id) {
        requireValidId(id);
        Task t = find(id);
        if (t.getStatus() != TaskStatus.FAILED && t.getStatus() != TaskStatus.CANCELLED) {
            throw new IllegalStateException("仅失败或已取消的任务可以重试");
        }
        if (runner.isInFlight(id)) {
            throw new IllegalStateException("任务正在执行中，请稍后再试");
        }
        repo.resetTaskForRetry(id);
        repo.resetStagesForRetry(id);
        runner.submit(repo.find(id).orElseThrow());
        return toView(repo.find(id).orElseThrow());
    }

    @PostMapping("/{id}/cancel")
    public TaskViews.TaskView cancel(@PathVariable String id) {
        requireValidId(id);
        Task t = find(id);
        runner.cancel(id);
        return toView(t);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) throws IOException {
        requireValidId(id);
        find(id);
        runner.cancel(id);
        repo.delete(id);
        deleteDirectoryRecursively(artifacts.taskDir(id));
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/artifacts/{name:.+}")
    public ResponseEntity<Object> artifact(@PathVariable String id,
                                           @PathVariable String name,
                                           HttpServletRequest request) throws IOException {
        requireValidId(id);
        find(id); // 任务不存在 → 404，且不触发目录创建（taskDir 会惰性建目录）
        if (!artifacts.isAllowedArtifactName(name)) {
            throw new IllegalArgumentException("非法的产物名称");
        }
        Path dir = artifacts.taskDir(id);
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new ArtifactNotFoundException("产物不存在: " + name);
        }
        long size = Files.size(file);
        MediaType type = MEDIA_TYPES.getOrDefault(
                MediaTypes.extension(name), MediaType.APPLICATION_OCTET_STREAM);

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null) {
            var m = RANGE_PATTERN.matcher(rangeHeader);
            if (m.matches()) {
                long start;
                long end;
                try {
                    start = m.group(1).isBlank() ? 0 : Long.parseLong(m.group(1));
                    end = m.group(2).isBlank() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
                } catch (NumberFormatException e) {
                    return rangeNotSatisfiable(size);
                }
                if (start > end || start >= size) {
                    return rangeNotSatisfiable(size);
                }
                long len = end - start + 1;
                var resource = new FileSystemResource(file);
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .contentType(type)
                        .contentLength(len)
                        .body(new ByteArrayRangeResource(resource, start, len));
            }
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(type)
                .contentLength(size)
                .body(new FileSystemResource(file));
    }

    private static ResponseEntity<Object> rangeNotSatisfiable(long size) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size).build();
    }

    private static void requireValidId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("非法的任务 ID");
        }
    }

    private static StageType parseStage(String raw) {
        try {
            return StageType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的功能类型：" + raw);
        }
    }

    /** 简单区间资源：从 start 起读 len 字节 */
    static final class ByteArrayRangeResource implements Resource {
        private final FileSystemResource delegate;
        private final long start;
        private final long length;

        ByteArrayRangeResource(FileSystemResource delegate, long start, long length) {
            this.delegate = delegate;
            this.start = start;
            this.length = length;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            var ch = Files.newByteChannel(delegate.getFile().toPath());
            ch.position(start);
            return new InputStream() {
                private long remaining = length;
                private final ByteBuffer bb = ByteBuffer.allocate(64 * 1024);

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) == -1 ? -1 : (one[0] & 0xff);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    if (!bb.hasRemaining()) {
                        bb.clear();
                        int n = ch.read(bb);
                        if (n == -1) return -1;
                        bb.flip();
                    }
                    int n = Math.min(len, bb.remaining());
                    bb.get(b, off, n);
                    remaining -= n;
                    return n;
                }

                @Override
                public void close() throws IOException {
                    ch.close();
                }
            };
        }

        @Override public boolean exists() { return delegate.exists(); }
        @Override public String getDescription() { return delegate.getDescription() + " (range)"; }
        @Override public File getFile() throws IOException { return delegate.getFile(); }
        @Override public long contentLength() { return length; }
        @Override public long lastModified() throws IOException { return delegate.lastModified(); }
        @Override public Resource createRelative(String relativePath) throws IOException { return delegate.createRelative(relativePath); }
        @Override public String getFilename() { return delegate.getFilename(); }
        @Override public URI getURI() throws IOException { return delegate.getURI(); }
        @Override public URL getURL() throws IOException { return delegate.getURL(); }
        @Override public boolean isReadable() { return delegate.isReadable(); }
        @Override public boolean isOpen() { return false; }
    }

    private Task find(String id) {
        return repo.find(id).orElseThrow(() -> new NoSuchElementException("任务不存在"));
    }

    private TaskViews.TaskView toView(Task t) {
        List<TaskViews.StageView> stages = repo.stagesOf(t.getId()).stream()
                .map(s -> new TaskViews.StageView(s.getStageType(), s.getStatus(), s.getDetail(), s.getArtifactPath()))
                .toList();
        List<TaskViews.ArtifactView> arts = artifacts.listArtifacts(t.getId()).stream()
                .map(a -> new TaskViews.ArtifactView(a.name(), a.sizeBytes()))
                .toList();
        return new TaskViews.TaskView(t.getId(), t.getTitle(), t.getSourceType().name(), t.getSourceRef(),
                t.getStatus().name(), t.getErrorMessage(),
                t.getEngineId(), t.getLanguage(), t.isDiarize(),
                iso(t.getCreatedAt()), iso(t.getStartedAt()), iso(t.getFinishedAt()), stages, arts);
    }

    private static String iso(Instant i) { return i == null ? null : i.toString(); }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 删除失败不阻塞任务删除
                }
            });
        }
    }
}
