package com.milviv.domain.exception;

import org.slf4j.event.Level;

public abstract class DomainException extends AppException {

    public DomainException(String message) {
        super(message);
    }

    public DomainException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    abstract public String getErrorCode();

    @Override
    public Level getLogLevel() {
        return Level.INFO;
    }
}
