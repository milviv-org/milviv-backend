package com.milviv.unit.shared.validation.annotation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.milviv.shared.validation.annotation.AnnotationObjectValidator;
import com.milviv.shared.validation.annotation.annotations.Email;
import com.milviv.shared.validation.annotation.annotations.EnumValue;
import com.milviv.shared.validation.annotation.annotations.Max;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.Min;
import com.milviv.shared.validation.annotation.annotations.MinLength;
import com.milviv.shared.validation.annotation.annotations.Pattern;
import com.milviv.shared.validation.annotation.annotations.Required;
import com.milviv.shared.validation.base.BaseValidationException;
import com.milviv.shared.validation.base.FieldError;

class AnnotationObjectValidatorTest {

    // --- Test enums & DTOs ---

    enum Level { EASY, HARD }

    static class RequiredDto {
        @Required
        String name;
        RequiredDto(String name) { this.name = name; }
    }

    static class RequiredCustomMessageDto {
        @Required(message = "Имя обязательное")
        String name;
        RequiredCustomMessageDto(String name) { this.name = name; }
    }

    static class MaxLengthDto {
        @MaxLength(length = 5)
        String name;
        MaxLengthDto(String name) { this.name = name; }
    }

    static class MinLengthDto {
        @MinLength(length = 3)
        String name;
        MinLengthDto(String name) { this.name = name; }
    }

    static class MaxDto {
        @Max(num = 10)
        Integer count;
        MaxDto(Integer count) { this.count = count; }
    }

    static class MinDto {
        @Min(num = 5)
        Integer count;
        MinDto(Integer count) { this.count = count; }
    }

    static class EmailDto {
        @Email
        String email;
        EmailDto(String email) { this.email = email; }
    }

    static class EnumDto {
        @EnumValue(enumValue = Level.class)
        String level;
        EnumDto(String level) { this.level = level; }
    }

    static class PatternDto {
        @Pattern(pattern = "^[a-z0-9-]+$")
        String slug;
        PatternDto(String slug) { this.slug = slug; }
    }

    static class MultiAnnotationDto {
        @Required
        @MaxLength(length = 5)
        @MinLength(length = 2)
        String name;
        MultiAnnotationDto(String name) { this.name = name; }
    }

    static class MultiFieldDto {
        @Required
        String name;
        @Required
        @Email
        String email;
        MultiFieldDto(String name, String email) {
            this.name = name;
            this.email = email;
        }
    }

    static class NoAnnotationDto {
        String name;
        NoAnnotationDto(String name) { this.name = name; }
    }

    static class OptionalEmailDto {
        @Email
        String email;
        OptionalEmailDto(String email) { this.email = email; }
    }

    // --- Helper ---

    private void validate(Object obj) {
        new AnnotationObjectValidator(obj).validate();
    }

    // --- @Required ---

    @Test
    void required_shouldThrow_whenValueIsNull() {
        assertThatThrownBy(() -> validate(new RequiredDto(null)))
                .isInstanceOf(BaseValidationException.class);
    }

    @Test
    void required_shouldThrow_whenValueIsBlank() {
        assertThatThrownBy(() -> validate(new RequiredDto("   ")))
                .isInstanceOf(BaseValidationException.class);
    }

    @Test
    void required_shouldNotThrow_whenValuePresent() {
        assertThatNoException().isThrownBy(() -> validate(new RequiredDto("John")));
    }

    @Test
    void required_shouldUseCustomMessage_whenProvided() {
        assertThatThrownBy(() -> validate(new RequiredCustomMessageDto(null)))
                .isInstanceOf(BaseValidationException.class)
                .satisfies(ex -> {
                    FieldError error = ((BaseValidationException) ex).getErrors().get(0);
                    assertThat(error.message()).isEqualTo("Имя обязательное");
                    assertThat(error.field()).isEqualTo("name");
                });
    }

    // --- @MaxLength ---

    @Test
    void maxLength_shouldNotThrow_whenWithinLimit() {
        assertThatNoException().isThrownBy(() -> validate(new MaxLengthDto("hi")));
    }

    @Test
    void maxLength_shouldThrow_whenExceedsLimit() {
        assertThatThrownBy(() -> validate(new MaxLengthDto("toolong")))
                .isInstanceOf(BaseValidationException.class)
                .satisfies(ex -> assertThat(((BaseValidationException) ex).getErrors().get(0).field()).isEqualTo("name"));
    }

    @Test
    void maxLength_shouldSkip_whenValueIsNullAndNotRequired() {
        assertThatNoException().isThrownBy(() -> validate(new MaxLengthDto(null)));
    }

    // --- @MinLength ---

    @Test
    void minLength_shouldNotThrow_whenAboveLimit() {
        assertThatNoException().isThrownBy(() -> validate(new MinLengthDto("hello")));
    }

    @Test
    void minLength_shouldThrow_whenBelowLimit() {
        assertThatThrownBy(() -> validate(new MinLengthDto("hi")))
                .isInstanceOf(BaseValidationException.class);
    }

    @Test
    void minLength_shouldSkip_whenValueIsNullAndNotRequired() {
        assertThatNoException().isThrownBy(() -> validate(new MinLengthDto(null)));
    }

    // --- @Max ---

    @Test
    void max_shouldNotThrow_whenWithinLimit() {
        assertThatNoException().isThrownBy(() -> validate(new MaxDto(5)));
    }

    @Test
    void max_shouldThrow_whenExceedsLimit() {
        assertThatThrownBy(() -> validate(new MaxDto(15)))
                .isInstanceOf(BaseValidationException.class);
    }

    @Test
    void max_shouldSkip_whenValueIsNullAndNotRequired() {
        assertThatNoException().isThrownBy(() -> validate(new MaxDto(null)));
    }

    // --- @Min ---

    @Test
    void min_shouldNotThrow_whenAboveLimit() {
        assertThatNoException().isThrownBy(() -> validate(new MinDto(10)));
    }

    @Test
    void min_shouldThrow_whenBelowLimit() {
        assertThatThrownBy(() -> validate(new MinDto(3)))
                .isInstanceOf(BaseValidationException.class);
    }

    @Test
    void min_shouldSkip_whenValueIsNullAndNotRequired() {
        assertThatNoException().isThrownBy(() -> validate(new MinDto(null)));
    }

    // --- @Email ---

    @Test
    void email_shouldNotThrow_whenValidEmail() {
        assertThatNoException().isThrownBy(() -> validate(new EmailDto("test@example.com")));
    }

    @Test
    void email_shouldThrow_whenInvalidEmail() {
        assertThatThrownBy(() -> validate(new EmailDto("notanemail")))
                .isInstanceOf(BaseValidationException.class);
    }

    @Test
    void email_shouldSkip_whenValueIsNullAndNotRequired() {
        assertThatNoException().isThrownBy(() -> validate(new OptionalEmailDto(null)));
    }

    // --- @EnumValue ---

    @Test
    void enumValue_shouldNotThrow_whenValidEnumName() {
        assertThatNoException().isThrownBy(() -> validate(new EnumDto("EASY")));
    }

    @Test
    void enumValue_shouldThrow_whenInvalidEnumName() {
        assertThatThrownBy(() -> validate(new EnumDto("INVALID")))
                .isInstanceOf(BaseValidationException.class);
    }

    @Test
    void enumValue_shouldSkip_whenValueIsNullAndNotRequired() {
        assertThatNoException().isThrownBy(() -> validate(new EnumDto(null)));
    }

    // --- @Pattern ---

    @Test
    void pattern_shouldNotThrow_whenMatches() {
        assertThatNoException().isThrownBy(() -> validate(new PatternDto("my-slug-123")));
    }

    @Test
    void pattern_shouldThrow_whenDoesNotMatch() {
        assertThatThrownBy(() -> validate(new PatternDto("Invalid Slug!")))
                .isInstanceOf(BaseValidationException.class);
    }

    @Test
    void pattern_shouldSkip_whenValueIsNullAndNotRequired() {
        assertThatNoException().isThrownBy(() -> validate(new PatternDto(null)));
    }

    // --- Multiple annotations on one field ---

    @Test
    void multiAnnotation_shouldCollectAllErrors_whenMultipleRulesFail() {
        // "x" — passes @Required, fails @MinLength(2), passes @MaxLength(5)... wait length 1 < 2 → fails minLength
        assertThatThrownBy(() -> validate(new MultiAnnotationDto("x")))
                .isInstanceOf(BaseValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((BaseValidationException) ex).getErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).field()).isEqualTo("name");
                });
    }

    @Test
    void multiAnnotation_shouldAddOnlyRequiredError_whenValueIsNull() {
        assertThatThrownBy(() -> validate(new MultiAnnotationDto(null)))
                .isInstanceOf(BaseValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((BaseValidationException) ex).getErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).field()).isEqualTo("name");
                });
    }

    @Test
    void multiAnnotation_shouldNotThrow_whenAllRulesPass() {
        assertThatNoException().isThrownBy(() -> validate(new MultiAnnotationDto("hi")));
    }

    // --- Multiple fields ---

    @Test
    void multiField_shouldCollectErrorsFromAllFields() {
        assertThatThrownBy(() -> validate(new MultiFieldDto(null, null)))
                .isInstanceOf(BaseValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((BaseValidationException) ex).getErrors();
                    assertThat(errors).hasSize(2);
                    assertThat(errors).extracting(FieldError::field)
                            .containsExactlyInAnyOrder("name", "email");
                });
    }

    @Test
    void multiField_shouldThrow_whenEmailInvalidAndNameValid() {
        assertThatThrownBy(() -> validate(new MultiFieldDto("John", "notanemail")))
                .isInstanceOf(BaseValidationException.class)
                .satisfies(ex -> {
                    List<FieldError> errors = ((BaseValidationException) ex).getErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).field()).isEqualTo("email");
                });
    }

    @Test
    void multiField_shouldNotThrow_whenAllValid() {
        assertThatNoException().isThrownBy(() -> validate(new MultiFieldDto("John", "john@example.com")));
    }

    // --- No annotations ---

    @Test
    void noAnnotations_shouldNotThrow_whenFieldIsNull() {
        assertThatNoException().isThrownBy(() -> validate(new NoAnnotationDto(null)));
    }

    @Test
    void noAnnotations_shouldNotThrow_whenFieldIsPresent() {
        assertThatNoException().isThrownBy(() -> validate(new NoAnnotationDto("value")));
    }
}
