package com.milviv.unit.infrastructure.config;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.milviv.application.ai.AiLimits;
import com.milviv.infrastructure.config.AiConfig;
import com.milviv.infrastructure.config.ConfigLoader;

class AiConfigTest {

    /** ConfigLoader без явных настроек: каждый getInt возвращает дефолт. */
    private static ConfigLoader defaults() {
        ConfigLoader loader = mock(ConfigLoader.class);
        when(loader.getInt(anyString(), anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(2));
        return loader;
    }

    @Test
    void shouldUseDefaultsFromConcept() {
        AiLimits limits = new AiConfig(defaults()).toLimits();

        assertThat(limits.request().messageMaxLength()).isEqualTo(2000);
        assertThat(limits.request().requestsPerDay()).isEqualTo(20);
        assertThat(limits.request().requestTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(limits.request().planTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(limits.data().maxRounds()).isEqualTo(2);
        assertThat(limits.data().maxItems()).isEqualTo(100);
        assertThat(limits.plan().maxCreates()).isEqualTo(40);
        assertThat(limits.plan().maxDeletes()).isEqualTo(10);
        assertThat(limits.plan().rewardCostMax()).isEqualTo(100_000);
    }

    @Test
    void shouldUseOverride() {
        ConfigLoader loader = defaults();
        when(loader.getInt(eq("AI_MAX_CREATES"), anyString(), anyInt())).thenReturn(30);

        assertThat(new AiConfig(loader).toLimits().plan().maxCreates()).isEqualTo(30);
    }

    @Test
    void shouldReject_nonPositive() {
        ConfigLoader loader = defaults();
        when(loader.getInt(eq("AI_MAX_DATA_ROUNDS"), anyString(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> new AiConfig(loader))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AI_MAX_DATA_ROUNDS");
    }

    @Test
    void shouldReject_minGreaterThanMax() {
        ConfigLoader perMinute = defaults();
        when(perMinute.getInt(eq("AI_USER_REQUESTS_PER_MINUTE"), anyString(), anyInt())).thenReturn(50);
        assertThatThrownBy(() -> new AiConfig(perMinute)).isInstanceOf(IllegalStateException.class);

        ConfigLoader tasks = defaults();
        when(tasks.getInt(eq("AI_MAX_TASKS_TOTAL"), anyString(), anyInt())).thenReturn(50);
        assertThatThrownBy(() -> new AiConfig(tasks)).isInstanceOf(IllegalStateException.class);
    }
}
