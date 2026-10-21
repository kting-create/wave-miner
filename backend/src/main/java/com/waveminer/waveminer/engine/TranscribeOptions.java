package com.waveminer.waveminer.engine;

public record TranscribeOptions(String language, boolean diarize) {
    public static TranscribeOptions of(String language, boolean diarize) {
        return new TranscribeOptions(language == null || language.isBlank() ? "auto" : language, diarize);
    }
}
