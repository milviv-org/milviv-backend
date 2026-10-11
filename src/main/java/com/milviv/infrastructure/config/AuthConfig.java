package com.milviv.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.milviv.application.auth.AuthPolicy;
import com.milviv.infrastructure.container.Inject;

/**
 * Настройки аутентификации. Источники и приоритет — как в AppConfig (ConfigLoader).
 * Все значения, кроме секрета, имеют дефолты.
 */
public class AuthConfig {

    /** HS256 требует ключ не короче выхода хеша — 256 бит (RFC 7518, 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    private final byte[] jwtSecret;
    private final String jwtIssuer;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Duration sessionMaxLifetime;
    private final boolean cookieSecure;
    private final int bcryptCost;
    private final int loginMaxAttempts;
    private final Duration loginWindow;

    @Inject
    public AuthConfig(ConfigLoader config) {
        String secret = config.get("AUTH_JWT_SECRET", "auth.jwt.secret");
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET is required and must be at least " + MIN_SECRET_BYTES
                    + " bytes. Generate one with: openssl rand -base64 48");
        }
        this.jwtSecret = secret.getBytes(StandardCharsets.UTF_8);
        this.jwtIssuer = config.get("AUTH_JWT_ISSUER", "auth.jwt.issuer", "milviv");
        this.accessTokenTtl = Duration.ofMinutes(config.getInt("AUTH_ACCESS_TOKEN_TTL_MINUTES", "auth.access.token.ttl.minutes", 15));
        this.refreshTokenTtl = Duration.ofDays(config.getInt("AUTH_REFRESH_TOKEN_TTL_DAYS", "auth.refresh.token.ttl.days", 30));
        this.sessionMaxLifetime = Duration.ofDays(config.getInt("AUTH_SESSION_MAX_DAYS", "auth.session.max.days", 90));
        this.cookieSecure = config.getBoolean("AUTH_COOKIE_SECURE", "auth.cookie.secure", true);
        this.bcryptCost = config.getInt("AUTH_BCRYPT_COST", "auth.bcrypt.cost", 12);
        this.loginMaxAttempts = config.getInt("AUTH_LOGIN_MAX_ATTEMPTS", "auth.login.max.attempts", 5);
        this.loginWindow = Duration.ofMinutes(config.getInt("AUTH_LOGIN_WINDOW_MINUTES", "auth.login.window.minutes", 15));

        validate();
    }

    private void validate() {
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()
                || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()
                || sessionMaxLifetime.compareTo(refreshTokenTtl) < 0) {
            throw new IllegalStateException("Token lifetimes must be positive and AUTH_SESSION_MAX_DAYS >= AUTH_REFRESH_TOKEN_TTL_DAYS");
        }
        if (bcryptCost < 10 || bcryptCost > 16) {
            throw new IllegalStateException("AUTH_BCRYPT_COST must be between 10 and 16");
        }
        if (loginMaxAttempts < 1) {
            throw new IllegalStateException("AUTH_LOGIN_MAX_ATTEMPTS must be >= 1");
        }
    }

    public AuthPolicy toPolicy() {
        return new AuthPolicy(accessTokenTtl, refreshTokenTtl, sessionMaxLifetime);
    }

    public byte[] getJwtSecret()           { return jwtSecret.clone(); }
    public String getJwtIssuer()           { return jwtIssuer; }
    public Duration getAccessTokenTtl()    { return accessTokenTtl; }
    public boolean isCookieSecure()        { return cookieSecure; }
    public int getBcryptCost()             { return bcryptCost; }
    public int getLoginMaxAttempts()       { return loginMaxAttempts; }
    public Duration getLoginWindow()       { return loginWindow; }

    @Override
    public String toString() {
        return "AuthConfig{jwtSecret='***', jwtIssuer='" + jwtIssuer + "', accessTokenTtl=" + accessTokenTtl
                + ", refreshTokenTtl=" + refreshTokenTtl + ", sessionMaxLifetime=" + sessionMaxLifetime
                + ", cookieSecure=" + cookieSecure + ", bcryptCost=" + bcryptCost
                + ", loginMaxAttempts=" + loginMaxAttempts + ", loginWindow=" + loginWindow + "}";
    }
}
