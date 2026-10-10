package com.milviv.unit.shared.validation.rules;

import com.milviv.shared.validation.base.rules.RequiredRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredRuleTest {

    private RequiredRule rule;

    @BeforeEach
    void setUp() {
        rule = new RequiredRule();
    }

    @Test
    void shouldReturnFalse_whenValueIsNull() {
        assertThat(rule.validate(null)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenStringIsEmpty() {
        assertThat(rule.validate("")).isFalse();
    }

    @Test
    void shouldReturnFalse_whenStringIsBlank() {
        assertThat(rule.validate("   ")).isFalse();
    }

    @Test
    void shouldReturnTrue_whenStringHasContent() {
        assertThat(rule.validate("hello")).isTrue();
    }

    @Test
    void shouldReturnFalse_whenCollectionIsEmpty() {
        assertThat(rule.validate(Collections.emptyList())).isFalse();
    }

    @Test
    void shouldReturnTrue_whenCollectionHasElements() {
        assertThat(rule.validate(List.of("item"))).isTrue();
    }

    @Test
    void shouldReturnTrue_whenValueIsNumber() {
        assertThat(rule.validate(42)).isTrue();
    }

    @Test
    void shouldReturnTrue_whenValueIsObject() {
        assertThat(rule.validate(new Object())).isTrue();
    }
}
