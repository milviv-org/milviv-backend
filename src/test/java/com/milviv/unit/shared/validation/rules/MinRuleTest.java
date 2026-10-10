package com.milviv.unit.shared.validation.rules;

import com.milviv.shared.validation.base.rules.MinRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinRuleTest {

    private MinRule rule;

    @BeforeEach
    void setUp() {
        rule = new MinRule();
    }

    @Test
    void shouldReturnTrue_whenValueEqualsLimit() {
        assertThat(rule.validate(5, 5)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenValueExceedsLimit() {
        assertThat(rule.validate(10, 5)).isTrue();
    }

    @Test
    void shouldReturnFalse_whenValueBelowLimit() {
        assertThat(rule.validate(3, 5)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenValueIsNull() {
        assertThat(rule.validate(null, 5)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenLimitIsNull() {
        assertThat(rule.validate(5, null)).isFalse();
    }

    @Test
    void shouldHandleDoubleValues() {
        assertThat(rule.validate(5.01, 5.0)).isTrue();
        assertThat(rule.validate(4.99, 5.0)).isFalse();
    }
}
