package com.waveminer.waveminer.media;

import com.waveminer.waveminer.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public class YtDlpService {

    public interface Runner {
        ProcessResult run(List<String> command, Duration timeout, BooleanSupplier cancelSignal);

        default ProcessResult run(List<String> command, Duration timeout) {
            return run(command, timeout, null);
        }
    }

    private final Runner runner;
    private final AppProperties props;

    public YtDlpService(Runner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    List<String> downloadCommand(String url, Path dir) {
        return List.of(props.getYtDlpPath(), "--no-playlist",
                "-f", "bv*+ba/b",
                "-o", dir.resolve("source.%(ext)s").toString(),
                "--newline", url);
    }

    public void download(String url, Path dir) {
        download(url, dir, null);
    }

    public void download(String url, Path dir, BooleanSupplier cancelSignal) {
        ProcessResult r = runner.run(downloadCommand(url, dir), props.processTimeout(), cancelSignal);
        if (r.exitCode() != 0) {
            throw new ProcessFailedException("yt-dlp 下载失败:\n" + tail(r.output()));
        }
    }

    /** 直链回退：流式下载到 dir/source.<ext> */
    public void directDownload(String url, Path dir) {
        String ext = extensionOfUrl(url);
        if (ext.isBlank() || !MediaTypes.ALL_EXTENSIONS.contains(ext)) {
            throw new ProcessFailedException("直链无法判断受支持的文件扩展名: " + url);
        }
        Path dest = dir.resolve("source." + ext);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(props.processTimeout())
                .GET().build();
        try {
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new ProcessFailedException("直链下载失败，HTTP " + resp.statusCode());
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ProcessFailedException("直链下载 IO 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessFailedException("直链下载被中断", e);
        }
    }

    public static boolean isDirectMediaUrl(String url) {
        try {
            String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
            int dot = path.lastIndexOf('.');
            return dot >= 0 && dot < path.length() - 1
                    && MediaTypes.ALL_EXTENSIONS.contains(path.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String extensionOfUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            int dot = path.lastIndexOf('.');
            return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }
}
