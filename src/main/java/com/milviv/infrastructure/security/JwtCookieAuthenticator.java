package com.milviv.infrastructure.security;

import java.time.Clock;
import java.util.UUID;

import com.milviv.domain.exception.UnauthorizedException;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.RequestAuthenticator;

/** Пользователь запроса — из access-токена в cookie. */
public class JwtCookieAuthenticator implements RequestAuthenticator {

    private final JwtAccessTokenService tokens;
    private final AuthCookies cookies;
    private final Clock clock;

    public JwtCookieAuthenticator(JwtAccessTokenService tokens, AuthCookies cookies, Clock clock) {
        this.tokens = tokens;
        this.cookies = cookies;
        this.clock = clock;
    }

    @Override
    public void authenticate(HttpContext ctx) {
        UUID userId = cookies.readAccessToken(ctx.getRequest())
                .flatMap(token -> tokens.verify(token, clock.instant()))
                .orElseThrow(() -> new UnauthorizedException("Требуется вход"));
        ctx.authenticateAs(userId);
    }
}
