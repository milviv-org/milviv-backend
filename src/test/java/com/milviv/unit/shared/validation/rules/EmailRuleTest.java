package com.milviv.unit.shared.validation.rules;

import com.milviv.shared.validation.base.rules.EmailRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailRuleTest {

    private EmailRule rule;

    @BeforeEach
    void setUp() {
        rule = new EmailRule();
    }

    @Test
    void shouldReturnTrue_whenEmailIsValid() {
        assertThat(rule.validate("user@example.com")).isTrue();
    }

    @Test
    void shouldReturnTrue_whenEmailHasSubdomain() {
        assertThat(rule.validate("user.name+tag@sub.domain.org")).isTrue();
    }

    @Test
    void shouldReturnFalse_whenEmailMissingAtSign() {
        assertThat(rule.validate("userexample.com")).isFalse();
    }

    @Test
    void shouldReturnFalse_whenEmailMissingDomain() {
        assertThat(rule.validate("user@")).isFalse();
    }

    @Test
    void shouldReturnFalse_whenEmailMissingLocal() {
        assertThat(rule.validate("@domain.com")).isFalse();
    }

    @Test
    void shouldReturnFalse_whenValueIsNull() {
        assertThat(rule.validate(null)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenValueIsBlank() {
        assertThat(rule.validate("   ")).isFalse();
    }

    @Test
    void shouldReturnFalse_whenEmailHasNoTld() {
        assertThat(rule.validate("user@domain")).isFalse();
    }
}
