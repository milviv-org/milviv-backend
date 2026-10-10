package com.milviv.unit.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.infrastructure.config.ConfigLoader;

/**
 * Unit tests for ConfigLoader.
 */
class ConfigLoaderTest {

    @BeforeEach
    void setUp() {
        // Clear any environment variables that might interfere with tests
        // Note: System.getenv() is read-only, so we test with properties file
    }

    @Test
    void shouldLoadPropertiesFromFile() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        String value = loader.getProperty("test.property");

        // then
        assertThat(value).isEqualTo("test-value");
    }

    @Test
    void shouldReturnNullWhenPropertyNotFound() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        String value = loader.get("NON_EXISTENT_ENV", "non.existent.property");

        // then
        assertThat(value).isNull();
    }

    @Test
    void shouldReturnDefaultValueWhenPropertyNotFound() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        String value = loader.get("NON_EXISTENT_ENV", "non.existent.property", "default-value");

        // then
        assertThat(value).isEqualTo("default-value");
    }

    @Test
    void shouldParseIntegerValue() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        int value = loader.getInt("TEST_INT", "test.int.property", 999);

        // then
        assertThat(value).isEqualTo(42);
    }

    @Test
    void shouldReturnDefaultIntWhenValueNotFound() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        int value = loader.getInt("NON_EXISTENT", "non.existent", 100);

        // then
        assertThat(value).isEqualTo(100);
    }

    @Test
    void shouldReturnDefaultIntWhenValueIsNotANumber() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        int value = loader.getInt("INVALID_INT", "test.invalid.int", 100);

        // then
        assertThat(value).isEqualTo(100);
    }

    @Test
    void shouldParseBooleanValue() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        boolean trueValue = loader.getBoolean("TEST_BOOL_TRUE", "test.bool.true", false);
        boolean falseValue = loader.getBoolean("TEST_BOOL_FALSE", "test.bool.false", true);

        // then
        assertThat(trueValue).isTrue();
        assertThat(falseValue).isFalse();
    }

    @Test
    void shouldReturnDefaultBooleanWhenValueNotFound() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        boolean value = loader.getBoolean("NON_EXISTENT", "non.existent", true);

        // then
        assertThat(value).isTrue();
    }

    @Test
    void shouldHandleMissingPropertiesFile() {
        // when
        ConfigLoader loader = new ConfigLoader("/non-existent-file.properties");

        // then - should not throw exception, just log warning
        String value = loader.get("ANY_KEY", "any.property", "default");
        assertThat(value).isEqualTo("default");
    }

    @Test
    void shouldUseDefaultConstructor() {
        // when
        ConfigLoader loader = new ConfigLoader();

        // then - should load /application.properties
        assertThat(loader).isNotNull();
    }

    @Test
    void shouldGetPropertyDirectly() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        String value = loader.getProperty("test.property");

        // then
        assertThat(value).isEqualTo("test-value");
    }

    @Test
    void shouldReturnNullForNonExistentProperty() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        String value = loader.getProperty("non.existent.property");

        // then
        assertThat(value).isNull();
    }

    @Test
    void shouldHandleEmptyPropertyValue() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        String value = loader.get("EMPTY_ENV", "test.empty.property");

        // then - empty string from properties file is returned as empty string
        assertThat(value).isEmpty();
    }

    @Test
    void shouldTrimWhitespaceFromProperties() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        String value = loader.getProperty("test.whitespace.property");

        // then
        assertThat(value).isEqualTo("value-with-spaces");
    }
}
