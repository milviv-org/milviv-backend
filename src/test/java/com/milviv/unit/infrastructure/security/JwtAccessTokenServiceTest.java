package com.milviv.unit.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.milviv.application.auth.AccessTokenIssuer.AccessToken;
import com.milviv.infrastructure.security.JwtAccessTokenService;

class JwtAccessTokenServiceTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final JwtAccessTokenService service = new JwtAccessTokenService(SECRET, "milviv", Duration.ofMinutes(15));
    private final UUID userId = UUID.randomUUID();

    @Test
    void verify_shouldReturnUserId_forFreshToken() {
        // given
        AccessToken token = service.issue(userId, NOW);

        // when / then
        assertThat(service.verify(token.value(), NOW.plusSeconds(60))).contains(userId);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void verify_shouldReject_whenExpired() {
        AccessToken token = service.issue(userId, NOW);

        assertThat(service.verify(token.value(), token.expiresAt())).isEmpty();
    }

    @Test
    void verify_shouldReject_whenSignedWithOtherSecret() {
        JwtAccessTokenService other = new JwtAccessTokenService(
                "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8), "milviv", Duration.ofMinutes(15));
        AccessToken foreign = other.issue(userId, NOW);

        assertThat(service.verify(foreign.value(), NOW)).isEmpty();
    }

    @Test
    void verify_shouldReject_whenIssuerDiffers() {
        JwtAccessTokenService other = new JwtAccessTokenService(SECRET, "someone-else", Duration.ofMinutes(15));

        assertThat(service.verify(other.issue(userId, NOW).value(), NOW)).isEmpty();
    }

    @Test
    void verify_shouldReject_whenPayloadTampered() {
        // given: подменён sub, подпись осталась старой
        String[] parts = service.issue(userId, NOW).value().split("\\.");
        String forgedPayload = base64("{\"iss\":\"milviv\",\"sub\":\"" + UUID.randomUUID() + "\",\"exp\":9999999999}");

        // when / then
        assertThat(service.verify(parts[0] + "." + forgedPayload + "." + parts[2], NOW)).isEmpty();
    }

    @Test
    void verify_shouldReject_algNone() {
        // given: классическая атака — alg none без подписи
        String header = base64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64("{\"iss\":\"milviv\",\"sub\":\"" + userId + "\",\"exp\":9999999999}");

        // when / then
        assertThat(service.verify(header + "." + payload + ".", NOW)).isEmpty();
    }

    @Test
    void verify_shouldReject_malformedTokens() {
        assertThat(service.verify(null, NOW)).isEmpty();
        assertThat(service.verify("", NOW)).isEmpty();
        assertThat(service.verify("abc", NOW)).isEmpty();
        assertThat(service.verify("a.b.c.d", NOW)).isEmpty();
        assertThat(service.verify("!!!.@@@.###", NOW)).isEmpty();
    }

    @Test
    void issue_shouldProduceUniqueTokens() {
        assertThat(service.issue(userId, NOW).value()).isNotEqualTo(service.issue(userId, NOW).value());
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
