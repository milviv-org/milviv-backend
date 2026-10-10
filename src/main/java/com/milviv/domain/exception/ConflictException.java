package com.milviv.domain.exception;

public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }

    @Override
    public String getErrorCode() {
        return "CONFLICT";
    }

}
