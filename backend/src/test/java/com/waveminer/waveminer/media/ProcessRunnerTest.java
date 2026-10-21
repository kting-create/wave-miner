package com.waveminer.waveminer.media;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    /** 跨平台 shell：Windows 用 cmd，Unix 用 sh（echo/sleep 在 Windows 无独立可执行文件） */
    private static List<String> shell(String script) {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win")
                ? List.of("cmd", "/c", script)
                : List.of("sh", "-c", script);
    }

    /** 跨平台长时运行命令：Windows 用 ping 模拟约 5 秒等待（timeout 命令在无控制台时不可用） */
    private static List<String> sleepFiveSeconds() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win")
                ? List.of("cmd", "/c", "ping", "-n", "6", "127.0.0.1")
                : List.of("sleep", "5");
    }

    /** 跨平台"stderr 输出后以退出码 3 结束" */
    private static List<String> echoBoomExit3() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win")
                ? List.of("cmd", "/c", "echo boom 1>&2 & exit 3")
                : List.of("sh", "-c", "echo boom >&2; exit 3");
    }

    @Test
    void capturesOutputAndExitCode() {
        ProcessResult r = runner.run(shell("echo hello wave"), Duration.ofSeconds(10));
        assertThat(r.exitCode()).isZero();
        assertThat(r.output()).contains("hello wave");
    }

    @Test
    void nonzeroExitCodeReturned() {
        ProcessResult r = runner.run(echoBoomExit3(), Duration.ofSeconds(10));
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.output()).contains("boom");
    }

    @Test
    void timeoutKillsProcess() {
        assertThatThrownBy(() ->
                runner.run(sleepFiveSeconds(), Duration.ofMillis(300)))
                .isInstanceOf(ProcessTimeoutException.class);
    }

    @Test
    void cancelSignalDestroysProcessBeforeTimeout() {
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
        ProcessResult r = runner.run(sleepFiveSeconds(), Duration.ofSeconds(30), cancel::get);
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
