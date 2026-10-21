package com.waveminer.waveminer.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final TaskRepository repo;

    public StartupRecovery(TaskRepository repo) { this.repo = repo; }

    @Override
    public void run(ApplicationArguments args) {
        repo.markStaleRunningAsFailed("应用重启导致中断，可重试");
        log.info("启动恢复检查完成");
    }
}
