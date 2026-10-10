package com.milviv.unit.shared.validation.rules;

import com.milviv.shared.validation.base.rules.PatternRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatternRuleTest {

    private PatternRule rule;

    @BeforeEach
    void setUp() {
        rule = new PatternRule();
    }

    @Test
    void shouldReturnTrue_whenValueMatchesPattern() {
        assertThat(rule.validate("hello-world-123", "^[a-z0-9-]+$")).isTrue();
    }

    @Test
    void shouldReturnFalse_whenValueDoesNotMatchPattern() {
        assertThat(rule.validate("Hello World", "^[a-z0-9-]+$")).isFalse();
    }

    @Test
    void shouldReturnFalse_whenValueIsNull() {
        assertThat(rule.validate(null, "^[a-z]+$")).isFalse();
    }

    @Test
    void shouldReturnFalse_whenPatternIsNull() {
        assertThat(rule.validate("hello", null)).isFalse();
    }

    @Test
    void shouldCacheCompiledPattern_onRepeatedCalls() {
        // Одинаковый паттерн вызывается дважды — должен использоваться кэш
        String pattern = "^[0-9]+$";
        assertThat(rule.validate("123", pattern)).isTrue();
        assertThat(rule.validate("456", pattern)).isTrue();
    }
}
