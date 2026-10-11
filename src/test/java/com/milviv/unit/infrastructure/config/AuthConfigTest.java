package com.milviv.unit.infrastructure.config;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.milviv.infrastructure.config.AuthConfig;
import com.milviv.infrastructure.config.ConfigLoader;

class AuthConfigTest {

    /** ConfigLoader без явных настроек: get(...) → null, остальное — дефолты. */
    private static ConfigLoader loaderWithSecret(String secret) {
        ConfigLoader loader = mock(ConfigLoader.class);
        when(loader.get("AUTH_JWT_SECRET", "auth.jwt.secret")).thenReturn(secret);
        when(loader.get(anyString(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(2));
        when(loader.getInt(anyString(), anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(2));
        when(loader.getBoolean(anyString(), anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(2));
        return loader;
    }

    @Test
    void shouldFailFast_whenSecretMissing() {
        assertThatThrownBy(() -> new AuthConfig(loaderWithSecret(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_SECRET");
    }

    @Test
    void shouldFailFast_whenSecretShorterThan256Bits() {
        assertThatThrownBy(() -> new AuthConfig(loaderWithSecret("short-secret")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldUseSecureDefaults() {
        AuthConfig config = new AuthConfig(loaderWithSecret("0123456789abcdef0123456789abcdef"));

        assertThat(config.getAccessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(config.toPolicy().refreshTokenTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(config.toPolicy().sessionMaxLifetime()).isEqualTo(Duration.ofDays(90));
        assertThat(config.isCookieSecure()).isTrue();
        assertThat(config.getBcryptCost()).isEqualTo(12);
        assertThat(config.getLoginMaxAttempts()).isEqualTo(5);
        assertThat(config.toString()).doesNotContain("0123456789abcdef");
    }

    @Test
    void shouldReject_weakBcryptCost() {
        ConfigLoader loader = loaderWithSecret("0123456789abcdef0123456789abcdef");
        when(loader.getInt(eq("AUTH_BCRYPT_COST"), anyString(), anyInt())).thenReturn(4);

        assertThatThrownBy(() -> new AuthConfig(loader)).isInstanceOf(IllegalStateException.class);
    }
}
