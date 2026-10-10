package com.milviv.shared.validation.base;

import java.util.List;

public class BaseValidationException extends RuntimeException {

    public final List<FieldError> errors;

    public BaseValidationException(String message, List<FieldError> errors) {
        super(message);
        this.errors = errors;
    }

    public BaseValidationException(String message, String field) {
        super("Validation failed");
        this.errors = List.of(new FieldError(message, field));
    }

    public List<FieldError> getErrors() {
        return errors;
    }
}
