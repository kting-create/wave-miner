package com.waveminer.waveminer.pipeline;

public interface StageHandler {
    StageType type();

    void run(TaskContext ctx);
}
