package com.milviv.infrastructure.migration;

import com.milviv.domain.exception.AppException;

public class MigrationException extends AppException {

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getErrorCode() {
        return "MIGRATION_FAILED";
    }
}

