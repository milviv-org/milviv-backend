package com.milviv.application.auth;

import java.time.Instant;
import java.util.UUID;

import com.milviv.application.auth.AccessTokenIssuer.AccessToken;
import com.milviv.domain.auth.RefreshToken;
import com.milviv.domain.auth.RefreshTokenRepository;

/**
 * Выдаёт пару токенов: для новой сессии или при ротации существующей.
 * Вызывается внутри транзакции сценария.
 */
public class SessionIssuer {

    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenIssuer accessTokenIssuer;
    private final AuthPolicy policy;

    public SessionIssuer(RefreshTokenRepository refreshTokens, AccessTokenIssuer accessTokenIssuer, AuthPolicy policy) {
        this.refreshTokens = refreshTokens;
        this.accessTokenIssuer = accessTokenIssuer;
        this.policy = policy;
    }

    public AuthTokens startSession(UUID userId, Instant now) {
        String rawRefresh = RefreshTokenCodec.generate();
        RefreshToken token = RefreshToken.startSession(
                userId,
                RefreshTokenCodec.hash(rawRefresh),
                now,
                now.plus(policy.refreshTokenTtl()),
                now.plus(policy.sessionMaxLifetime())
        );
        refreshTokens.add(token);
        return tokens(userId, now, rawRefresh, token);
    }

    public AuthTokens rotate(RefreshToken current, Instant now) {
        String rawRefresh = RefreshTokenCodec.generate();
        RefreshToken next = current.rotate(RefreshTokenCodec.hash(rawRefresh), now, now.plus(policy.refreshTokenTtl()));
        refreshTokens.revoke(current.getId(), now);
        refreshTokens.add(next);
        return tokens(current.getUserId(), now, rawRefresh, next);
    }

    private AuthTokens tokens(UUID userId, Instant now, String rawRefresh, RefreshToken refresh) {
        AccessToken access = accessTokenIssuer.issue(userId, now);
        return new AuthTokens(access.value(), access.expiresAt(), rawRefresh, refresh.getExpiresAt());
    }
}
