package com.milviv.shared.validation.complex;

import java.util.List;
import java.util.function.Function;

import com.milviv.shared.validation.base.BaseValidationException;
import com.milviv.shared.validation.base.FieldError;
import com.milviv.shared.validation.base.ValidateErrorText;

public interface ComplexObjectValidatorInterface<T> {

    <R> ComplexObjectValidatorInterface<T> required(Function<T, R> getter, String fieldName, String message);

    default <R> ComplexObjectValidatorInterface<T> required(Function<T, R> getter, String fieldName) {
        return required(getter, fieldName, ValidateErrorText.REQUIRED.format());
    }

    ComplexObjectValidatorInterface<T> maxLength(Function<T, String> getter, String fieldName, Integer length, String message);

    default ComplexObjectValidatorInterface<T> maxLength(Function<T, String> getter, String fieldName, Integer length) {
        return maxLength(getter, fieldName, length, ValidateErrorText.MAX_LENGTH.format(length.toString()));
    }

    ComplexObjectValidatorInterface<T> minLength(Function<T, String> getter, String fieldName, Integer length, String message);

    default ComplexObjectValidatorInterface<T> minLength(Function<T, String> getter, String fieldName, Integer length) {
        return minLength(getter, fieldName, length, ValidateErrorText.MIN_LENGTH.format(length.toString()));
    }

    ComplexObjectValidatorInterface<T> max(Function<T, Number> getter, String fieldName, Integer num, String message);

    default ComplexObjectValidatorInterface<T> max(Function<T, Number> getter, String fieldName, Integer num) {
        return max(getter, fieldName, num, ValidateErrorText.MAX.format(num.toString()));
    }

    ComplexObjectValidatorInterface<T> min(Function<T, Number> getter, String fieldName, Integer num, String message);

    default ComplexObjectValidatorInterface<T> min(Function<T, Number> getter, String fieldName, Integer num) {
        return min(getter, fieldName, num, ValidateErrorText.MIN.format(num.toString()));
    }

    ComplexObjectValidatorInterface<T> email(Function<T, String> getter, String fieldName, String message);

    default ComplexObjectValidatorInterface<T> email(Function<T, String> getter, String fieldName) {
        return email(getter, fieldName, ValidateErrorText.EMAIL.format());
    }

    <R> ComplexObjectValidatorInterface<T> enumValue(Function<T, R> getter, String fieldName, Class<? extends Enum<?>> enumClass, String message);

    default <R> ComplexObjectValidatorInterface<T> enumValue(Function<T, R> getter, String fieldName, Class<? extends Enum<?>> enumClass) {
        return enumValue(getter, fieldName, enumClass, ValidateErrorText.ENUM.format());
    }

    ComplexObjectValidatorInterface<T> pattern(Function<T, String> getter, String fieldName, String pattern, String message);

    default ComplexObjectValidatorInterface<T> pattern(Function<T, String> getter, String fieldName, String pattern) {
        return pattern(getter, fieldName, pattern, ValidateErrorText.PATTERN.format());
    }

    List<FieldError> getFieldErrors();

    void throwIfErrored() throws BaseValidationException;
}
