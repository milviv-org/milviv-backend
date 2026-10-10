package com.milviv.shared.validation.annotation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.milviv.shared.validation.annotation.annotations.Email;
import com.milviv.shared.validation.annotation.annotations.EnumValue;
import com.milviv.shared.validation.annotation.annotations.Max;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.Min;
import com.milviv.shared.validation.annotation.annotations.MinLength;
import com.milviv.shared.validation.annotation.annotations.Pattern;
import com.milviv.shared.validation.annotation.annotations.Required;
import com.milviv.shared.validation.base.BaseValidationException;
import com.milviv.shared.validation.base.BaseValidator;
import com.milviv.shared.validation.base.BaseValidatorInterface;
import com.milviv.shared.validation.base.FieldError;
import com.milviv.shared.validation.base.ValidateErrorText;

public class AnnotationObjectValidator implements AnnotationObjectValidatorInterface {

    private final Object object;
    private final BaseValidatorInterface baseValidator = new BaseValidator();

    public AnnotationObjectValidator(Object object) {
        this.object = object;
    }

    @Override
    public void validate() throws BaseValidationException {
        List<FieldError> fieldErrors = new ArrayList<>();

        for (Field field : object.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object value = field.get(object);
                validateField(field, value, fieldErrors);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot access field: " + field.getName(), e);
            }
        }

        if (!fieldErrors.isEmpty()) {
            throw new BaseValidationException("Validation failed", fieldErrors);
        }
    }

    private void validateField(Field field, Object value, List<FieldError> errors) {
        String fieldName = field.getName();

        if (field.isAnnotationPresent(Required.class)) {
            Required ann = field.getAnnotation(Required.class);

            if (!baseValidator.required(value)) {
                String msg = ann.message().isBlank() ? ValidateErrorText.REQUIRED.format() : ann.message();
                errors.add(new FieldError(msg, fieldName));
            }
        }

        if (!baseValidator.required(value)) {
            return;
        }

        if (field.isAnnotationPresent(MaxLength.class)) {
            MaxLength ann = field.getAnnotation(MaxLength.class);

            if (!baseValidator.maxLength((String) value, ann.length())) {
                String msg = ann.message().isBlank() ? ValidateErrorText.MAX_LENGTH.format(ann.length()) : ann.message();
                errors.add(new FieldError(msg, fieldName));
            }
        }

        if (field.isAnnotationPresent(MinLength.class)) {
            MinLength ann = field.getAnnotation(MinLength.class);

            if (!baseValidator.minLength((String) value, ann.length())) {
                String msg = ann.message().isBlank() ? ValidateErrorText.MIN_LENGTH.format(ann.length()) : ann.message();
                errors.add(new FieldError(msg, fieldName));
            }
        }

        if (field.isAnnotationPresent(Max.class)) {
            Max ann = field.getAnnotation(Max.class);

            if (!baseValidator.max((Number) value, ann.num())) {
                String msg = ann.message().isBlank() ? ValidateErrorText.MAX.format(ann.num()) : ann.message();
                errors.add(new FieldError(msg, fieldName));
            }
        }

        if (field.isAnnotationPresent(Min.class)) {
            Min ann = field.getAnnotation(Min.class);

            if (!baseValidator.min((Number) value, ann.num())) {
                String msg = ann.message().isBlank() ? ValidateErrorText.MIN.format(ann.num()) : ann.message();
                errors.add(new FieldError(msg, fieldName));
            }
        }

        if (field.isAnnotationPresent(Email.class)) {
            Email ann = field.getAnnotation(Email.class);

            if (!baseValidator.email((String) value)) {
                String msg = ann.message().isBlank() ? ValidateErrorText.EMAIL.format() : ann.message();
                errors.add(new FieldError(msg, fieldName));
            }
        }

        if (field.isAnnotationPresent(EnumValue.class)) {
            EnumValue ann = field.getAnnotation(EnumValue.class);

            if (!baseValidator.enumValue(value, ann.enumValue())) {
                String msg = ann.message().isBlank() ? ValidateErrorText.ENUM.format() : ann.message();
                errors.add(new FieldError(msg, fieldName));
            }
        }

        if (field.isAnnotationPresent(Pattern.class)) {
            Pattern ann = field.getAnnotation(Pattern.class);

            if (!baseValidator.pattern((String) value, ann.pattern())) {
                String msg = ann.message().isBlank() ? ValidateErrorText.PATTERN.format() : ann.message();
                errors.add(new FieldError(msg, fieldName));
            }
        }
    }
}
