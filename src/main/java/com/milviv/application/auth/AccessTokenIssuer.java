package com.milviv.application.auth;

import java.time.Instant;
import java.util.UUID;

/** Выпуск access-токена. Реализация — в infrastructure (JWT). */
public interface AccessTokenIssuer {

    AccessToken issue(UUID userId, Instant now);

    record AccessToken(String value, Instant expiresAt) {}
}
