package com.milviv.unit.shared.validation.rules;

import com.milviv.shared.validation.base.rules.MinLengthRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinLengthRuleTest {

    private MinLengthRule rule;

    @BeforeEach
    void setUp() {
        rule = new MinLengthRule();
    }

    @Test
    void shouldReturnTrue_whenLengthEqualsLimit() {
        assertThat(rule.validate("hello", 5)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenLengthExceedsLimit() {
        assertThat(rule.validate("hello world", 5)).isTrue();
    }

    @Test
    void shouldReturnFalse_whenLengthBelowLimit() {
        assertThat(rule.validate("hi", 5)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenValueIsNull() {
        assertThat(rule.validate(null, 5)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenLengthIsNull() {
        assertThat(rule.validate("hello", null)).isFalse();
    }
}
