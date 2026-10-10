package com.milviv.unit.shared.validation.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.shared.validation.annotation.AnnotationObjectValidator;
import com.milviv.shared.validation.annotation.AnnotationObjectValidatorInterface;
import com.milviv.shared.validation.annotation.AnnotationValidator;

class AnnotationValidatorTest {

    private AnnotationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AnnotationValidator();
    }

    @Test
    void validateByObject_shouldReturnNonNull() {
        AnnotationObjectValidatorInterface result = validator.validateByObject(new Object());
        assertThat(result).isNotNull();
    }

    @Test
    void validateByObject_shouldReturnAnnotationObjectValidator() {
        AnnotationObjectValidatorInterface result = validator.validateByObject(new Object());
        assertThat(result).isInstanceOf(AnnotationObjectValidator.class);
    }

    @Test
    void validateByObject_shouldReturnNewInstanceEachTime() {
        AnnotationObjectValidatorInterface first = validator.validateByObject(new Object());
        AnnotationObjectValidatorInterface second = validator.validateByObject(new Object());
        assertThat(first).isNotSameAs(second);
    }
}
