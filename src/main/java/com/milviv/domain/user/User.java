package com.milviv.domain.user;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.milviv.domain.shared.VersionedAggregate;

/**
 * Пользователь. Входит по email и паролю.
 *
 * Email хранится нормализованным (без пробелов по краям, в нижнем регистре),
 * поэтому уникальность и поиск не зависят от регистра.
 */
public class User extends VersionedAggregate {

    private final UUID id;
    private final String email;
    private final String name;
    private final String passwordHash;
    private final Instant createdAt;
    private final Instant updatedAt;

    /** Восстановление из БД. Вызывается только репозиторием. */
    public User(UUID id, String email, String name, String passwordHash,
                Instant createdAt, Instant updatedAt, long version) {
        super(version);
        this.id = id;
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Регистрация нового пользователя. Пароль приходит уже захешированным. */
    public static User register(String email, String name, String passwordHash, Instant now) {
        return new User(UUID.randomUUID(), normalizeEmail(email), name.trim(), passwordHash, now, now, 0L);
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public UUID getId()             { return id; }
    public String getEmail()        { return email; }
    public String getName()         { return name; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getUpdatedAt()   { return updatedAt; }
}
