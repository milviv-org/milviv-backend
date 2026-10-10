package com.milviv.domain.user;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.shared.VersionedAggregate;

public class User extends VersionedAggregate {

    private final UUID id;
    private String username;
    private String email;
    private String passwordHash;
    private final Instant createdAt;
    private Instant updatedAt;

    /**
     * Используется репозиторием для восстановления объекта из БД (reconstitution).
     * Не вызывать напрямую из бизнес-логики — только из UserJdbcRepository.
     */
    public User(UUID id, String username, String email, String passwordHash,
                Instant createdAt, Instant updatedAt, long version) {
        super(version);
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Фабричный метод — единственный способ создать нового пользователя.
     * Гарантирует корректное начальное состояние агрегата.
     */
    public static User create(String username, String email, String passwordHash) {
        return new User(
            UUID.randomUUID(),
            username,
            email,
            passwordHash,
            Instant.now(),
            Instant.now(),
            0L
        );
    }

    public UUID getId()             { return id; }
    public String getUsername()     { return username; }
    public String getEmail()        { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getUpdatedAt()   { return updatedAt; }
}
