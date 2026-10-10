package com.milviv.domain.exception;

import java.time.Instant;

import org.slf4j.event.Level;

public abstract class AppException extends RuntimeException {

    private final Instant timestamp = Instant.now();

    public AppException(String message) {
        super(message);
    }

    public AppException(String message, Throwable cause) {
        super(message, cause);
    }

    abstract public String getErrorCode();

    public Level getLogLevel() {
        return Level.ERROR;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
