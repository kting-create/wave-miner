package com.waveminer.waveminer.engine;

import java.nio.file.Path;

public interface TranscriptionEngine {
    String id();

    EngineInfo info();

    TranscribeResult transcribe(Path mediaFile, TranscribeOptions options, ProgressCallback progress);
}
