package com.milviv.unit.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.milviv.application.auth.AuthTokens;
import com.milviv.domain.exception.TooManyRequestsException;
import com.milviv.infrastructure.security.AuthCookies;
import com.milviv.infrastructure.security.BCryptPasswordHasher;
import com.milviv.infrastructure.security.InMemoryLoginAttemptLimiter;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class SecurityComponentsTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    // ─── BCrypt ───────────────────────────────────────────────────────────────

    @Test
    void bcrypt_shouldHashWithSalt_andVerify() {
        BCryptPasswordHasher hasher = new BCryptPasswordHasher(4); // минимальный cost — тесты быстрые

        String first = hasher.hash("correct horse");
        String second = hasher.hash("correct horse");

        assertThat(first).isNotEqualTo("correct horse").isNotEqualTo(second);
        assertThat(hasher.matches("correct horse", first)).isTrue();
        assertThat(hasher.matches("wrong", first)).isFalse();
        assertThat(hasher.matches("correct horse", "not-a-bcrypt-hash")).isFalse();
    }

    // ─── Ограничение попыток ──────────────────────────────────────────────────

    @Test
    void limiter_shouldBlockAfterMaxFailures_andUnblockAfterWindow() {
        MutableClock clock = new MutableClock(NOW);
        InMemoryLoginAttemptLimiter limiter = new InMemoryLoginAttemptLimiter(3, Duration.ofMinutes(15), clock);

        limiter.recordFailure("a@b.c");
        limiter.recordFailure("a@b.c");
        limiter.checkAllowed("a@b.c"); // 2 из 3 — ещё можно
        limiter.recordFailure("a@b.c");

        assertThatThrownBy(() -> limiter.checkAllowed("a@b.c")).isInstanceOf(TooManyRequestsException.class);
        limiter.checkAllowed("other@b.c"); // другой email не затронут

        clock.advance(Duration.ofMinutes(15));
        limiter.checkAllowed("a@b.c");
    }

    @Test
    void limiter_shouldResetOnSuccess() {
        InMemoryLoginAttemptLimiter limiter = new InMemoryLoginAttemptLimiter(1, Duration.ofMinutes(15), new MutableClock(NOW));

        limiter.recordFailure("a@b.c");
        limiter.reset("a@b.c");

        limiter.checkAllowed("a@b.c");
    }

    // ─── Cookie ───────────────────────────────────────────────────────────────

    @Test
    void cookies_shouldBeHttpOnlySecureStrict_withPrefixes() {
        // given
        HttpServletResponse response = mock(HttpServletResponse.class);
        List<String> headers = captureSetCookie(response);
        AuthTokens tokens = new AuthTokens("acc", NOW.plusSeconds(900), "ref", NOW.plus(Duration.ofDays(30)));

        // when
        new AuthCookies(true).write(response, tokens, NOW);

        // then
        assertThat(headers).containsExactly(
                "__Host-access_token=acc; Path=/; Max-Age=900; HttpOnly; Secure; SameSite=Strict",
                "__Secure-refresh_token=ref; Path=/auth; Max-Age=2592000; HttpOnly; Secure; SameSite=Strict");
    }

    @Test
    void cookies_withoutSecure_shouldUsePlainNames() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        List<String> headers = captureSetCookie(response);

        new AuthCookies(false).clear(response);

        assertThat(headers).containsExactly(
                "access_token=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict",
                "refresh_token=; Path=/auth; Max-Age=0; HttpOnly; SameSite=Strict");
    }

    @Test
    void cookies_shouldReadOwnCookiesOnly() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("access_token", "fake-without-prefix"),
                new Cookie("__Host-access_token", "real"),
                new Cookie("__Secure-refresh_token", "")
        });

        AuthCookies cookies = new AuthCookies(true);

        assertThat(cookies.readAccessToken(request)).contains("real");
        assertThat(cookies.readRefreshToken(request)).isEmpty();
    }

    @Test
    void cookies_shouldHandleRequestWithoutCookies() {
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThat(new AuthCookies(true).readAccessToken(request)).isEmpty();
    }

    private static List<String> captureSetCookie(HttpServletResponse response) {
        List<String> headers = new ArrayList<>();
        doAnswer(inv -> headers.add(inv.getArgument(1)))
                .when(response).addHeader(eq("Set-Cookie"), anyString());
        return headers;
    }

    /** Часы, которые двигает тест. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
