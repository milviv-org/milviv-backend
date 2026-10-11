package com.milviv.domain.goal;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.shared.VersionedAggregate;

/**
 * Цель — долгосрочный результат. Объединяет проекты и задачи, баллов не даёт.
 * При удалении цели её проекты и задачи остаются без цели (G-15).
 */
public class Goal extends VersionedAggregate {

    private final UUID id;
    private final UUID userId;
    private final Instant createdAt;

    private String title;
    private String description;
    private Instant updatedAt;

    /** Восстановление из БД. Вызывается только репозиторием. */
    public Goal(UUID id, UUID userId, String title, String description,
                Instant createdAt, Instant updatedAt, long version) {
        super(version);
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Goal create(UUID userId, GoalDetails details, Instant now) {
        Goal goal = new Goal(UUID.randomUUID(), userId, null, null, now, now, 0L);
        goal.edit(details, now);
        return goal;
    }

    public void edit(GoalDetails details, Instant now) {
        this.title = details.title().trim();
        this.description = details.description();
        this.updatedAt = now;
    }

    public UUID getId()            { return id; }
    public UUID getUserId()        { return userId; }
    public String getTitle()       { return title; }
    public String getDescription() { return description; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
}
