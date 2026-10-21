package com.waveminer.waveminer.engine;

public class EngineException extends RuntimeException {
    private final String rawResponse;

    public EngineException(String message) { this(message, null, null); }

    public EngineException(String message, String rawResponse) { this(message, null, rawResponse); }

    public EngineException(String message, Throwable cause, String rawResponse) {
        super(message, cause);
        this.rawResponse = rawResponse;
    }

    public String getRawResponse() { return rawResponse; }
}
