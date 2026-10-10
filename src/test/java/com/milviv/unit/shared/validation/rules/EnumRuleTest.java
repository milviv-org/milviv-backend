package com.milviv.unit.shared.validation.rules;

import com.milviv.shared.validation.base.rules.EnumRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnumRuleTest {

    enum Level { EASY, MEDIUM, HARD }

    private EnumRule rule;

    @BeforeEach
    void setUp() {
        rule = new EnumRule();
    }

    @Test
    void shouldReturnTrue_whenValueIsValidEnumConstantName() {
        assertThat(rule.validate("EASY", Level.class)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenValueIsEnumInstance() {
        assertThat(rule.validate(Level.HARD, Level.class)).isTrue();
    }

    @Test
    void shouldReturnFalse_whenValueIsInvalidConstantName() {
        assertThat(rule.validate("UNKNOWN", Level.class)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenValueIsNull() {
        assertThat(rule.validate(null, Level.class)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenValueIsCaseMismatch() {
        // Имена констант в Java — UPPER_CASE, нижний регистр не совпадает
        assertThat(rule.validate("easy", Level.class)).isFalse();
    }
}
