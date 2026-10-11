package com.milviv.application.auth;

import java.time.Instant;

/** Пара токенов, выданная при входе или обновлении. */
public record AuthTokens(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {}
