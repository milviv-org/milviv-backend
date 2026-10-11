package com.milviv.domain.gamification;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.shared.VersionedAggregate;

/**
 * Награда за баллы. Стоимость задаёт пользователь.
 *
 * Правила:
 * - стоимость 1..MAX_COST;
 * - получить можно сколько угодно раз — каждый раз списывается стоимость;
 * - скрытая награда видна только с паролем скрытых наград и недоступна AI.
 */
public class Reward extends VersionedAggregate {

    public static final int MIN_COST = 1;
    public static final int MAX_COST = 1_000_000;

    private final UUID id;
    private final UUID userId;
    private final Instant createdAt;

    private String title;
    private String description;
    private int cost;
    private boolean hidden;
    private Instant updatedAt;

    /** Восстановление из БД. Вызывается только репозиторием. */
    public Reward(UUID id, UUID userId, String title, String description, int cost, boolean hidden,
                  Instant createdAt, Instant updatedAt, long version) {
        super(version);
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.description = description;
        this.cost = cost;
        this.hidden = hidden;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Reward create(UUID userId, RewardDetails details, Instant now) {
        Reward reward = new Reward(UUID.randomUUID(), userId, null, null, 0, false, now, now, 0L);
        reward.edit(details, now);
        return reward;
    }

    public void edit(RewardDetails details, Instant now) {
        if (details.cost() < MIN_COST || details.cost() > MAX_COST) {
            throw new ValidationException("cost", "Стоимость должна быть от " + MIN_COST + " до " + MAX_COST);
        }
        this.title = details.title().trim();
        this.description = details.description();
        this.cost = details.cost();
        this.hidden = details.hidden();
        this.updatedAt = now;
    }

    /** Текст записи в истории баллов. Название скрытой награды туда не попадает. */
    public String redemptionReason() {
        return hidden ? PointsEntry.HIDDEN_REWARD_REASON : "Reward redeemed: " + title;
    }

    public UUID getId()            { return id; }
    public UUID getUserId()        { return userId; }
    public String getTitle()       { return title; }
    public String getDescription() { return description; }
    public int getCost()           { return cost; }
    public boolean isHidden()      { return hidden; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
}
