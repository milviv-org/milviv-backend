package com.milviv.shared.validation.complex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import com.milviv.shared.validation.base.BaseValidationException;
import com.milviv.shared.validation.base.BaseValidator;
import com.milviv.shared.validation.base.FieldError;

public class ComplexObjectValidator<T> implements ComplexObjectValidatorInterface<T> {

    private final BaseValidator baseValidator = new BaseValidator();
    private final T object;
    private final List<FieldError> fieldErrors = new ArrayList<>();

    public ComplexObjectValidator(T object) {
        this.object = object;
    }

    private void addError(String message, String field) {
        fieldErrors.add(new FieldError(message, field));
    }

    @Override
    public <R> ComplexObjectValidatorInterface<T> required(Function<T, R> getter, String fieldName, String message) {
        if (!baseValidator.required(getter.apply(object))) {
            addError(message, fieldName);
        }

        return this;
    }

    @Override
    public ComplexObjectValidatorInterface<T> maxLength(Function<T, String> getter, String fieldName, Integer length, String message) {
        if (!baseValidator.maxLength(getter.apply(object), length)) {
            addError(message, fieldName);
        }

        return this;
    }

    @Override
    public ComplexObjectValidatorInterface<T> minLength(Function<T, String> getter, String fieldName, Integer length, String message) {
        if (!baseValidator.minLength(getter.apply(object), length)) {
            addError(message, fieldName);
        }

        return this;
    }

    @Override
    public ComplexObjectValidatorInterface<T> max(Function<T, Number> getter, String fieldName, Integer num, String message) {
        if (!baseValidator.max(getter.apply(object), num)) {
            addError(message, fieldName);
        }

        return this;
    }

    @Override
    public ComplexObjectValidatorInterface<T> min(Function<T, Number> getter, String fieldName, Integer num, String message) {
        if (!baseValidator.min(getter.apply(object), num)) {
            addError(message, fieldName);
        }

        return this;
    }

    @Override
    public ComplexObjectValidatorInterface<T> email(Function<T, String> getter, String fieldName, String message) {
        if (!baseValidator.email(getter.apply(object))) {
            addError(message, fieldName);
        }

        return this;
    }

    @Override
    public <R> ComplexObjectValidatorInterface<T> enumValue(Function<T, R> getter, String fieldName, Class<? extends Enum<?>> enumClass, String message) {
        if (!baseValidator.enumValue(getter.apply(object), enumClass)) {
            addError(message, fieldName);
        }

        return this;
    }

    @Override
    public ComplexObjectValidatorInterface<T> pattern(Function<T, String> getter, String fieldName, String pattern, String message) {
        if (!baseValidator.pattern(getter.apply(object), pattern)) {
            addError(message, fieldName);
        }

        return this;
    }

    @Override
    public void throwIfErrored() throws BaseValidationException {
        if (!fieldErrors.isEmpty()) {
            throw new BaseValidationException("Validation error", fieldErrors);
        }
    }

    @Override
    public List<FieldError> getFieldErrors() {
        return Collections.unmodifiableList(fieldErrors);
    }

}
