package com.waveminer.waveminer.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class ProcessRunner {

    public ProcessResult run(List<String> command, Duration timeout) {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ProcessFailedException("无法启动进程: " + command.get(0) + "（请确认已安装并在 PATH 中）", e);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        AtomicReference<IOException> readError = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                }
            } catch (IOException e) {
                readError.set(e);
            }
        });
        reader.setDaemon(true);
        reader.setName("process-reader-" + command.get(0));
        reader.start();
        boolean finished;
        try {
            finished = p.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            throw new ProcessFailedException("进程等待被中断: " + command.get(0), e);
        }
        if (!finished) {
            p.destroyForcibly();
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            throw new ProcessTimeoutException("进程超时（" + timeout.toMillis() + "ms）: " + command.get(0));
        }
        try {
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            throw new ProcessFailedException("进程输出读取被中断: " + command.get(0), e);
        }
        if (readError.get() != null) {
            p.destroyForcibly();
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            throw new UncheckedIOException(readError.get());
        }
        return new ProcessResult(p.exitValue(), buffer.toString(StandardCharsets.UTF_8));
    }
}
