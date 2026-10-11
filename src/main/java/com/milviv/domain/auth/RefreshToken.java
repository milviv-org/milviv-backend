package com.milviv.domain.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh-токен одной сессии входа.
 *
 * В БД хранится только SHA-256 хеш токена — сам токен знает только браузер.
 * При каждом обновлении токен заменяется новым (ротация) в той же семье (family).
 * Предъявление уже заменённого токена означает кражу: отзывается вся семья.
 *
 * expiresAt — срок этого токена (продлевается при ротации);
 * sessionExpiresAt — абсолютный срок сессии, после него нужен новый вход.
 */
public final class RefreshToken {

    private final UUID id;
    private final UUID userId;
    private final UUID familyId;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant sessionExpiresAt;
    private final Instant revokedAt;

    public RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash,
                        Instant createdAt, Instant expiresAt, Instant sessionExpiresAt, Instant revokedAt) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.sessionExpiresAt = sessionExpiresAt;
        this.revokedAt = revokedAt;
    }

    /** Первый токен новой сессии. */
    public static RefreshToken startSession(UUID userId, String tokenHash, Instant now,
                                            Instant expiresAt, Instant sessionExpiresAt) {
        return new RefreshToken(UUID.randomUUID(), userId, UUID.randomUUID(), tokenHash,
                now, earliest(expiresAt, sessionExpiresAt), sessionExpiresAt, null);
    }

    /** Следующий токен той же сессии. Не может пережить абсолютный срок сессии. */
    public RefreshToken rotate(String newTokenHash, Instant now, Instant newExpiresAt) {
        return new RefreshToken(UUID.randomUUID(), userId, familyId, newTokenHash,
                now, earliest(newExpiresAt, sessionExpiresAt), sessionExpiresAt, null);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    public UUID getId()                  { return id; }
    public UUID getUserId()              { return userId; }
    public UUID getFamilyId()            { return familyId; }
    public String getTokenHash()         { return tokenHash; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getExpiresAt()        { return expiresAt; }
    public Instant getSessionExpiresAt() { return sessionExpiresAt; }
    public Instant getRevokedAt()        { return revokedAt; }
}
