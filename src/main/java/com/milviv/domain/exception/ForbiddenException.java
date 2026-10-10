package com.milviv.domain.exception;

public class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super(message);
    }

    @Override
    public String getErrorCode() {
        return "FORBIDDEN";
    }

}
