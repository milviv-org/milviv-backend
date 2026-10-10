package com.milviv.unit.shared.validation.complex;

import com.milviv.shared.validation.base.BaseValidationException;
import com.milviv.shared.validation.base.FieldError;
import com.milviv.shared.validation.complex.ComplexObjectValidator;
import com.milviv.shared.validation.complex.ComplexObjectValidatorInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ComplexObjectValidatorTest {

    enum Level { EASY, MEDIUM, HARD }

    record TestDto(
        String name,
        String description,
        String email,
        String slug,
        Integer count,
        Level level
    ) {}

    private TestDto validDto;

    @BeforeEach
    void setUp() {
        validDto = new TestDto("John", "Some description", "john@example.com", "john-doe", 5, Level.EASY);
    }

    // --- required ---

    @Test
    void required_shouldNotAddError_whenValuePresent() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        v.required(TestDto::name, "name");
        assertThat(v.getFieldErrors()).isEmpty();
    }

    @Test
    void required_shouldAddError_whenValueIsNull() {
        TestDto dto = new TestDto(null, "desc", "a@b.com", "slug", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.required(TestDto::name, "name");
        assertThat(v.getFieldErrors()).hasSize(1);
        assertThat(v.getFieldErrors().get(0).field()).isEqualTo("name");
    }

    @Test
    void required_shouldAddError_withCustomMessage() {
        TestDto dto = new TestDto(null, "desc", "a@b.com", "slug", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.required(TestDto::name, "name", "Имя обязательное");
        assertThat(v.getFieldErrors().get(0).message()).isEqualTo("Имя обязательное");
    }

    // --- maxLength ---

    @Test
    void maxLength_shouldNotAddError_whenWithinLimit() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        v.maxLength(TestDto::name, "name", 50);
        assertThat(v.getFieldErrors()).isEmpty();
    }

    @Test
    void maxLength_shouldAddError_whenExceedsLimit() {
        TestDto dto = new TestDto("toolongname", "desc", "a@b.com", "slug", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.maxLength(TestDto::name, "name", 5);
        assertThat(v.getFieldErrors()).hasSize(1);
        assertThat(v.getFieldErrors().get(0).field()).isEqualTo("name");
    }

    // --- minLength ---

    @Test
    void minLength_shouldNotAddError_whenAboveLimit() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        v.minLength(TestDto::name, "name", 2);
        assertThat(v.getFieldErrors()).isEmpty();
    }

    @Test
    void minLength_shouldAddError_whenBelowLimit() {
        TestDto dto = new TestDto("Jo", "desc", "a@b.com", "slug", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.minLength(TestDto::name, "name", 5);
        assertThat(v.getFieldErrors()).hasSize(1);
    }

    // --- max ---

    @Test
    void max_shouldNotAddError_whenWithinLimit() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        v.max(TestDto::count, "count", 10);
        assertThat(v.getFieldErrors()).isEmpty();
    }

    @Test
    void max_shouldAddError_whenExceedsLimit() {
        TestDto dto = new TestDto("name", "desc", "a@b.com", "slug", 20, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.max(TestDto::count, "count", 10);
        assertThat(v.getFieldErrors()).hasSize(1);
    }

    // --- min ---

    @Test
    void min_shouldNotAddError_whenAboveLimit() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        v.min(TestDto::count, "count", 1);
        assertThat(v.getFieldErrors()).isEmpty();
    }

    @Test
    void min_shouldAddError_whenBelowLimit() {
        TestDto dto = new TestDto("name", "desc", "a@b.com", "slug", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.min(TestDto::count, "count", 5);
        assertThat(v.getFieldErrors()).hasSize(1);
    }

    // --- email ---

    @Test
    void email_shouldNotAddError_whenValidEmail() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        v.email(TestDto::email, "email");
        assertThat(v.getFieldErrors()).isEmpty();
    }

    @Test
    void email_shouldAddError_whenInvalidEmail() {
        TestDto dto = new TestDto("name", "desc", "notanemail", "slug", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.email(TestDto::email, "email");
        assertThat(v.getFieldErrors()).hasSize(1);
    }

    // --- pattern ---

    @Test
    void pattern_shouldNotAddError_whenMatches() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        v.pattern(TestDto::slug, "slug", "^[a-z0-9-]+$");
        assertThat(v.getFieldErrors()).isEmpty();
    }

    @Test
    void pattern_shouldAddError_whenDoesNotMatch() {
        TestDto dto = new TestDto("name", "desc", "a@b.com", "Invalid Slug!", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.pattern(TestDto::slug, "slug", "^[a-z0-9-]+$");
        assertThat(v.getFieldErrors()).hasSize(1);
    }

    // --- enumValue ---

    @Test
    void enumValue_shouldNotAddError_whenValidConstant() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        v.enumValue(TestDto::level, "level", Level.class);
        assertThat(v.getFieldErrors()).isEmpty();
    }

    @Test
    void enumValue_shouldAddError_whenValueIsNull() {
        TestDto dto = new TestDto("name", "desc", "a@b.com", "slug", 1, null);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.enumValue(TestDto::level, "level", Level.class);
        assertThat(v.getFieldErrors()).hasSize(1);
    }

    // --- throwIfErrored ---

    @Test
    void throwIfErrored_shouldNotThrow_whenNoErrors() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        assertThatNoException().isThrownBy(v::throwIfErrored);
    }

    @Test
    void throwIfErrored_shouldThrowBaseValidationException_whenErrorsExist() {
        TestDto dto = new TestDto(null, null, "notanemail", "slug", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.required(TestDto::name, "name")
         .required(TestDto::description, "description")
         .email(TestDto::email, "email");

        assertThatThrownBy(v::throwIfErrored)
            .isInstanceOf(BaseValidationException.class)
            .satisfies(ex -> {
                List<FieldError> errors = ((BaseValidationException) ex).getErrors();
                assertThat(errors).hasSize(3);
                assertThat(errors).extracting(FieldError::field)
                    .containsExactly("name", "description", "email");
            });
    }

    @Test
    void throwIfErrored_shouldCollectAllErrors_beforeThrowing() {
        TestDto dto = new TestDto(null, null, "a@b.com", "slug", 1, Level.EASY);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);
        v.required(TestDto::name, "name")
         .required(TestDto::description, "description");

        assertThatThrownBy(v::throwIfErrored)
            .isInstanceOf(BaseValidationException.class)
            .satisfies(ex -> {
                assertThat(((BaseValidationException) ex).getErrors()).hasSize(2);
            });
    }

    // --- getFieldErrors ---

    @Test
    void getFieldErrors_shouldReturnUnmodifiableList() {
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(validDto);
        List<FieldError> errors = v.getFieldErrors();
        assertThatThrownBy(() -> errors.add(new FieldError("msg", "field")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- fluent chain ---

    @Test
    void fluentChain_shouldAccumulateMultipleErrors() {
        TestDto dto = new TestDto(null, "x", "bad", "BAD SLUG", 100, null);
        ComplexObjectValidatorInterface<TestDto> v = new ComplexObjectValidator<>(dto);

        v.required(TestDto::name, "name")
         .minLength(TestDto::description, "description", 5)
         .email(TestDto::email, "email")
         .pattern(TestDto::slug, "slug", "^[a-z0-9-]+$")
         .max(TestDto::count, "count", 10)
         .enumValue(TestDto::level, "level", Level.class);

        assertThat(v.getFieldErrors()).hasSize(6);
    }
}
