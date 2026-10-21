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
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class ProcessRunner {

    public ProcessResult run(List<String> command, Duration timeout) {
        return run(command, timeout, null);
    }

    public ProcessResult run(List<String> command, Duration timeout, BooleanSupplier cancelSignal) {
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
        Thread watcher = cancelSignal == null
                ? null : startCancelWatcher(p, timeout, cancelSignal, command.get(0));
        boolean finished;
        try {
            finished = p.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(p);
            throw new ProcessFailedException("进程等待被中断: " + command.get(0), e);
        } finally {
            if (watcher != null) {
                try {
                    watcher.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (!finished) {
            kill(p);
            throw new ProcessTimeoutException("进程超时（" + timeout.toMillis() + "ms）: " + command.get(0));
        }
        try {
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(p);
            throw new ProcessFailedException("进程输出读取被中断: " + command.get(0), e);
        }
        if (readError.get() != null) {
            kill(p);
            throw new UncheckedIOException(readError.get());
        }
        return new ProcessResult(p.exitValue(), buffer.toString(StandardCharsets.UTF_8));
    }

    private static Thread startCancelWatcher(Process p, Duration timeout,
                                             BooleanSupplier cancelSignal, String name) {
        return Thread.ofVirtual().name("process-cancel-watcher-" + name).start(() -> {
            long deadline = System.nanoTime() + Math.max(1, timeout.toMillis()) * 1_000_000L;
            try {
                while (!p.waitFor(50, TimeUnit.MILLISECONDS)) {
                    if (cancelSignal.getAsBoolean()) {
                        kill(p);
                    }
                    if (System.nanoTime() - deadline >= 0) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void kill(Process p) {
        p.destroyForcibly();
        p.descendants().forEach(ProcessHandle::destroyForcibly);
    }
}
