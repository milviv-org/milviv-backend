package com.milviv.infrastructure.exception;

import com.milviv.domain.exception.AppException;

public class DatabaseException extends AppException {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getErrorCode() {
        return "DATABASE";
    }
}
