package com.milviv.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import com.milviv.application.auth.AuthTokens;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Токены передаются только в cookie — JavaScript их не видит (защита от кражи через XSS).
 *
 * Атрибуты (OWASP Session Management Cheat Sheet):
 * - HttpOnly — недоступны JavaScript;
 * - Secure — только по HTTPS (браузеры разрешают и http://localhost);
 * - SameSite=Strict — не отправляются с запросами с чужих сайтов (защита от CSRF);
 * - префиксы __Host- / __Secure- — браузер не даст подменить cookie с поддомена или по HTTP;
 * - refresh-токен ограничен путём /auth: на остальные запросы он не уходит.
 */
public class AuthCookies {

    static final String ACCESS = "access_token";
    static final String REFRESH = "refresh_token";
    static final String REFRESH_PATH = "/auth";

    private final boolean secure;
    private final String accessName;
    private final String refreshName;

    public AuthCookies(boolean secure) {
        this.secure = secure;
        // Префиксы требуют Secure, поэтому без Secure используются обычные имена
        this.accessName = secure ? "__Host-" + ACCESS : ACCESS;
        this.refreshName = secure ? "__Secure-" + REFRESH : REFRESH;
    }

    public void write(HttpServletResponse response, AuthTokens tokens, Instant now) {
        response.addHeader("Set-Cookie", cookie(accessName, tokens.accessToken(), "/", secondsUntil(tokens.accessTokenExpiresAt(), now)));
        response.addHeader("Set-Cookie", cookie(refreshName, tokens.refreshToken(), REFRESH_PATH, secondsUntil(tokens.refreshTokenExpiresAt(), now)));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie(accessName, "", "/", 0));
        response.addHeader("Set-Cookie", cookie(refreshName, "", REFRESH_PATH, 0));
    }

    public Optional<String> readAccessToken(HttpServletRequest request) {
        return read(request, accessName);
    }

    public Optional<String> readRefreshToken(HttpServletRequest request) {
        return read(request, refreshName);
    }

    private String cookie(String name, String value, String path, long maxAgeSeconds) {
        return name + "=" + value
                + "; Path=" + path
                + "; Max-Age=" + maxAgeSeconds
                + "; HttpOnly"
                + (secure ? "; Secure" : "")
                + "; SameSite=Strict";
    }

    private static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isEmpty())
                .findFirst();
    }

    private static long secondsUntil(Instant expiresAt, Instant now) {
        return Math.max(0, Duration.between(now, expiresAt).getSeconds());
    }
}
