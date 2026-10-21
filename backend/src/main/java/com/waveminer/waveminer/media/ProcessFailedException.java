package com.waveminer.waveminer.media;

public class ProcessFailedException extends RuntimeException {
    public ProcessFailedException(String message) { super(message); }
    public ProcessFailedException(String message, Throwable cause) { super(message, cause); }
}
