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

    /** ConfigLoader без явных настроек: каждый get и getInt возвращает дефолт, секретов нет. */
    private static ConfigLoader defaults() {
        ConfigLoader loader = mock(ConfigLoader.class);
        when(loader.getInt(anyString(), anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(2));
        when(loader.get(anyString(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(2));
        return loader;
    }

    private static ConfigLoader gemini() {
        ConfigLoader loader = defaults();
        when(loader.get(eq("AI_PROVIDER"), anyString(), anyString())).thenReturn("gemini");
        when(loader.get(eq("AI_MODEL"), anyString(), anyString())).thenReturn("gemini-flash-test");
        when(loader.getSecret("GEMINI_API_KEY")).thenReturn("secret-key");
        return loader;
    }

    @Test
    void providerIsNone_byDefault_andNeedsNoKey() {
        AiConfig config = new AiConfig(defaults());

        assertThat(config.provider()).isEqualTo(AiConfig.PROVIDER_NONE);
        assertThatThrownBy(config::toGeminiSettings).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void gemini_usesConceptDefaults_andHidesKey() {
        AiConfig config = new AiConfig(gemini());
        var settings = config.toGeminiSettings();

        assertThat(settings.model()).isEqualTo("gemini-flash-test");
        assertThat(settings.baseUrl().toString()).isEqualTo("https://generativelanguage.googleapis.com");
        assertThat(settings.callTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.retries()).isEqualTo(2);
        assertThat(settings.maxOutputTokens()).isEqualTo(8192);
        assertThat(settings.temperature()).isEqualTo(0.4);
        assertThat(settings.responseMaxBytes()).isEqualTo(65_536);
        assertThat(config.toString()).doesNotContain("secret-key");
    }

    @Test
    void gemini_requiresKey_andValidModel() {
        ConfigLoader noKey = gemini();
        when(noKey.getSecret("GEMINI_API_KEY")).thenReturn(null);
        assertThatThrownBy(() -> new AiConfig(noKey)).hasMessageContaining("GEMINI_API_KEY");

        ConfigLoader badModel = gemini();
        when(badModel.get(eq("AI_MODEL"), anyString(), anyString())).thenReturn("../../evil?x=1");
        assertThatThrownBy(() -> new AiConfig(badModel)).hasMessageContaining("AI_MODEL");

        ConfigLoader unknown = defaults();
        when(unknown.get(eq("AI_PROVIDER"), anyString(), anyString())).thenReturn("openai");
        assertThatThrownBy(() -> new AiConfig(unknown)).hasMessageContaining("AI_PROVIDER");
    }

    @Test
    void gemini_checksTimeoutAndTemperature() {
        ConfigLoader slowCall = gemini();
        when(slowCall.getInt(eq("AI_CALL_TIMEOUT_MS"), anyString(), anyInt())).thenReturn(120_000);
        assertThatThrownBy(() -> new AiConfig(slowCall)).hasMessageContaining("AI_CALL_TIMEOUT_MS");

        ConfigLoader hot = gemini();
        when(hot.get(eq("AI_TEMPERATURE"), anyString(), anyString())).thenReturn("2.5");
        assertThatThrownBy(() -> new AiConfig(hot)).hasMessageContaining("AI_TEMPERATURE");
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

    @Test
    void memoryLimits_haveDefaults_andMustBePositive() {
        assertThat(new AiConfig(defaults()).toLimits().memory()).isEqualTo(new AiLimits.Memory(20, 500));

        ConfigLoader loader = defaults();
        when(loader.getInt(eq("AI_MEMORY_MAX_ITEMS"), anyString(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> new AiConfig(loader)).hasMessageContaining("AI_MEMORY_MAX_ITEMS");
    }
}
