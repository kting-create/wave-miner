package com.waveminer.waveminer.engine;

@FunctionalInterface
public interface ProgressCallback {
    void update(String message);

    default boolean cancelled() { return false; }

    static ProgressCallback noop() { return m -> {}; }
}
