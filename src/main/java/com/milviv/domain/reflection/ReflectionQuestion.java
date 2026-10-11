package com.milviv.domain.reflection;

import java.time.Instant;
import java.util.UUID;

/**
 * Вопрос рефлексии. Стандартный (userId = null) общий для всех и не меняется;
 * свой вопрос пользователь может изменить или удалить.
 */
public class ReflectionQuestion {

    public static final int MAX_TEXT_LENGTH = 500;

    private final UUID id;
    private final UUID userId;
    private final Instant createdAt;
    private String text;

    /** Восстановление из БД. Вызывается только репозиторием. */
    public ReflectionQuestion(UUID id, UUID userId, String text, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.text = text;
        this.createdAt = createdAt;
    }

    public static ReflectionQuestion create(UUID userId, String text, Instant now) {
        return new ReflectionQuestion(UUID.randomUUID(), userId, text.trim(), now);
    }

    /** Ответы хранят свою копию текста — правка их не меняет. */
    public void edit(String text) {
        if (isStandard()) {
            throw new IllegalStateException("Standard questions are read-only");
        }
        this.text = text.trim();
    }

    public boolean isStandard() {
        return userId == null;
    }

    public UUID getId()           { return id; }
    public UUID getUserId()       { return userId; }
    public String getText()       { return text; }
    public Instant getCreatedAt() { return createdAt; }
}
