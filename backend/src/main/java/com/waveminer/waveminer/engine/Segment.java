package com.waveminer.waveminer.engine;

public record Segment(long startMs, long endMs, String text, Integer speaker) {}
