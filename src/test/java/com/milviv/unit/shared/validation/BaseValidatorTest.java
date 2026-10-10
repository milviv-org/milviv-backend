package com.milviv.unit.shared.validation;

import com.milviv.shared.validation.base.BaseValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaseValidatorTest {

    enum Level { EASY, MEDIUM, HARD }

    private BaseValidator validator;

    @BeforeEach
    void setUp() {
        validator = new BaseValidator();
    }

    // --- required ---

    @Test
    void required_shouldReturnFalse_whenNull() {
        assertThat(validator.required(null)).isFalse();
    }

    @Test
    void required_shouldReturnTrue_whenValuePresent() {
        assertThat(validator.required("value")).isTrue();
    }

    // --- maxLength ---

    @Test
    void maxLength_shouldReturnTrue_whenWithinLimit() {
        assertThat(validator.maxLength("hello", 10)).isTrue();
    }

    @Test
    void maxLength_shouldReturnFalse_whenExceedsLimit() {
        assertThat(validator.maxLength("toolongstring", 5)).isFalse();
    }

    // --- minLength ---

    @Test
    void minLength_shouldReturnTrue_whenAboveLimit() {
        assertThat(validator.minLength("hello", 3)).isTrue();
    }

    @Test
    void minLength_shouldReturnFalse_whenBelowLimit() {
        assertThat(validator.minLength("hi", 5)).isFalse();
    }

    // --- max ---

    @Test
    void max_shouldReturnTrue_whenValueWithinLimit() {
        assertThat(validator.max(5, 10)).isTrue();
    }

    @Test
    void max_shouldReturnFalse_whenValueExceedsLimit() {
        assertThat(validator.max(15, 10)).isFalse();
    }

    // --- min ---

    @Test
    void min_shouldReturnTrue_whenValueAboveLimit() {
        assertThat(validator.min(10, 5)).isTrue();
    }

    @Test
    void min_shouldReturnFalse_whenValueBelowLimit() {
        assertThat(validator.min(2, 5)).isFalse();
    }

    // --- email ---

    @Test
    void email_shouldReturnTrue_whenValidEmail() {
        assertThat(validator.email("user@example.com")).isTrue();
    }

    @Test
    void email_shouldReturnFalse_whenInvalidEmail() {
        assertThat(validator.email("notanemail")).isFalse();
    }

    // --- pattern ---

    @Test
    void pattern_shouldReturnTrue_whenMatches() {
        assertThat(validator.pattern("slug-123", "^[a-z0-9-]+$")).isTrue();
    }

    @Test
    void pattern_shouldReturnFalse_whenDoesNotMatch() {
        assertThat(validator.pattern("Slug 123", "^[a-z0-9-]+$")).isFalse();
    }

    // --- enumValue ---

    @Test
    void enumValue_shouldReturnTrue_whenValidConstant() {
        assertThat(validator.enumValue("EASY", Level.class)).isTrue();
    }

    @Test
    void enumValue_shouldReturnFalse_whenInvalidConstant() {
        assertThat(validator.enumValue("UNKNOWN", Level.class)).isFalse();
    }
}
