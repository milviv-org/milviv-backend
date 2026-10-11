package com.milviv.domain.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище refresh-токенов. Реализация — в infrastructure.
 * Методы вызываются внутри TransactionManager.execute().
 */
public interface RefreshTokenRepository {

    void add(RefreshToken token);

    /**
     * Найти токен по хешу и заблокировать строку до конца транзакции:
     * два параллельных обновления одного токена выполняются по очереди.
     */
    Optional<RefreshToken> findByHashForUpdate(String tokenHash);

    void revoke(UUID tokenId, Instant revokedAt);

    /** Отозвать все ещё действующие токены сессии. */
    void revokeFamily(UUID familyId, Instant revokedAt);
}
