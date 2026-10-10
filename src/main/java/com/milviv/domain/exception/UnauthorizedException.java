package com.milviv.domain.exception;

public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String message) {
        super(message);
    }

    @Override
    public String getErrorCode() {
        return "UNAUTHORIZED";
    }

}
