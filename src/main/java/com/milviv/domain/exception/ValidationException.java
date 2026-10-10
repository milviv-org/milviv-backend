package com.milviv.domain.exception;

import java.util.List;

public class ValidationException extends DomainException {

    private final List<FieldError> errors;

    public ValidationException(List<FieldError> errors) {
        this.errors = errors;
        super("Validation failed");
    }

    public ValidationException(String field, String message) {
        this.errors = List.of(new FieldError(field, message));
        super("Validation failed");
    }

    @Override
    public String getErrorCode() {
        return "VALIDATION";
    }

    public List<FieldError> getErrors() {
        return errors;
    }

}
