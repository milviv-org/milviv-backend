package com.milviv.application.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.auth.RefreshToken;
import com.milviv.domain.auth.RefreshTokenRepository;
import com.milviv.domain.exception.UnauthorizedException;

/**
 * Обновление пары токенов по refresh-токену с ротацией.
 *
 * Каждый refresh-токен одноразовый. Если предъявлен уже заменённый токен —
 * его украли (или украли заменивший его): отзывается вся сессия,
 * и вор, и пользователь должны войти заново (OAuth 2.0 Security BCP, RFC 9700).
 */
public class RefreshSession {

    private static final Logger logger = LoggerFactory.getLogger(RefreshSession.class);
    static final String SESSION_INVALID = "Сессия недействительна, войдите заново";

    private final RefreshTokenRepository refreshTokens;
    private final SessionIssuer sessionIssuer;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public RefreshSession(RefreshTokenRepository refreshTokens, SessionIssuer sessionIssuer,
                          TransactionManager transactionManager, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.sessionIssuer = sessionIssuer;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public AuthTokens execute(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new UnauthorizedException(SESSION_INVALID);
        }
        String hash = RefreshTokenCodec.hash(rawRefreshToken);
        Instant now = clock.instant();

        // Отзыв сессии при повторном использовании должен закоммититься,
        // поэтому ошибка бросается после транзакции, а не внутри неё.
        Optional<AuthTokens> result = transactionManager.execute(() -> {
            Optional<RefreshToken> found = refreshTokens.findByHashForUpdate(hash);
            if (found.isEmpty()) {
                return Optional.<AuthTokens>empty();
            }
            RefreshToken token = found.get();

            if (token.isRevoked()) {
                logger.warn("Refresh token reuse detected, revoking session family {}", token.getFamilyId());
                refreshTokens.revokeFamily(token.getFamilyId(), now);
                return Optional.<AuthTokens>empty();
            }
            if (token.isExpired(now)) {
                return Optional.<AuthTokens>empty();
            }
            return Optional.of(sessionIssuer.rotate(token, now));
        });

        return result.orElseThrow(() -> new UnauthorizedException(SESSION_INVALID));
    }
}
