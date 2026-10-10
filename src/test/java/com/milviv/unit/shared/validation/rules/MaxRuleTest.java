package com.milviv.unit.shared.validation.rules;

import com.milviv.shared.validation.base.rules.MaxRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaxRuleTest {

    private MaxRule rule;

    @BeforeEach
    void setUp() {
        rule = new MaxRule();
    }

    @Test
    void shouldReturnTrue_whenValueEqualsLimit() {
        assertThat(rule.validate(10, 10)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenValueBelowLimit() {
        assertThat(rule.validate(5, 10)).isTrue();
    }

    @Test
    void shouldReturnFalse_whenValueExceedsLimit() {
        assertThat(rule.validate(11, 10)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenValueIsNull() {
        assertThat(rule.validate(null, 10)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenLimitIsNull() {
        assertThat(rule.validate(10, null)).isFalse();
    }

    @Test
    void shouldHandleDoubleValues() {
        assertThat(rule.validate(9.99, 10.0)).isTrue();
        assertThat(rule.validate(10.01, 10.0)).isFalse();
    }
}
