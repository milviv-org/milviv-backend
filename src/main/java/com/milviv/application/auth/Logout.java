package com.milviv.application.auth;

import java.time.Clock;
import java.time.Instant;

import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.auth.RefreshTokenRepository;

/**
 * Выход: отозвать сессию, к которой относится refresh-токен.
 * Идемпотентен: без токена или с неизвестным токеном просто ничего не делает.
 */
public class Logout {

    private final RefreshTokenRepository refreshTokens;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public Logout(RefreshTokenRepository refreshTokens, TransactionManager transactionManager, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public void execute(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String hash = RefreshTokenCodec.hash(rawRefreshToken);
        Instant now = clock.instant();

        transactionManager.execute(() ->
                refreshTokens.findByHashForUpdate(hash)
                        .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), now)));
    }
}
