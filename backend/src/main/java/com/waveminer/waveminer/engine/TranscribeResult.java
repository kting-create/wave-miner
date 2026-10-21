package com.waveminer.waveminer.engine;

import java.util.List;

public record TranscribeResult(List<Segment> segments, String rawResponse) {}
