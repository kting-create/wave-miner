package com.waveminer.waveminer.media;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void capturesOutputAndExitCode() {
        ProcessResult r = runner.run(List.of("echo", "hello wave"), Duration.ofSeconds(10));
        assertThat(r.exitCode()).isZero();
        assertThat(r.output()).contains("hello wave");
    }

    @Test
    void nonzeroExitCodeReturned() {
        ProcessResult r = runner.run(List.of("sh", "-c", "echo boom >&2; exit 3"), Duration.ofSeconds(10));
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.output()).contains("boom");
    }

    @Test
    void timeoutKillsProcess() {
        assertThatThrownBy(() ->
                runner.run(List.of("sleep", "5"), Duration.ofMillis(300)))
                .isInstanceOf(ProcessTimeoutException.class);
    }

    @Test
    void cancelSignalDestroysProcessBeforeTimeout() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean(false);
        Thread flipper = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancel.set(true);
        });
        flipper.setDaemon(true);
        flipper.start();
        long start = System.nanoTime();
        ProcessResult r = runner.run(List.of("sleep", "5"), Duration.ofSeconds(30), cancel::get);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isLessThan(5000);
        assertThat(r.exitCode()).isNotZero();
    }

    @Test
    void missingBinaryFails() {
        assertThatThrownBy(() ->
                runner.run(List.of("definitely-not-a-real-binary-xyz"), Duration.ofSeconds(5)))
                .isInstanceOf(ProcessFailedException.class);
    }
}
