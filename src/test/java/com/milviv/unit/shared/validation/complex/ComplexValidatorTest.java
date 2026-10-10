package com.milviv.unit.shared.validation.complex;

import com.milviv.shared.validation.complex.ComplexObjectValidatorInterface;
import com.milviv.shared.validation.complex.ComplexValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComplexValidatorTest {

    record TestDto(String name) {}

    private ComplexValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ComplexValidator();
    }

    @Test
    void validateByObject_shouldReturnComplexObjectValidator() {
        TestDto dto = new TestDto("value");
        ComplexObjectValidatorInterface<TestDto> result = validator.validateByObject(dto);
        assertThat(result).isNotNull();
    }

    @Test
    void validateByObject_shouldReturnNewInstancePerCall() {
        TestDto dto = new TestDto("value");
        ComplexObjectValidatorInterface<TestDto> first = validator.validateByObject(dto);
        ComplexObjectValidatorInterface<TestDto> second = validator.validateByObject(dto);
        assertThat(first).isNotSameAs(second);
    }
}
