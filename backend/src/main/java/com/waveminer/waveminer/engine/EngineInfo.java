package com.waveminer.waveminer.engine;

import java.util.List;

public record EngineInfo(String id, String displayName, long maxDurationSec, long maxSizeBytes,
                         boolean supportsDiarization, List<String> languages) {}
