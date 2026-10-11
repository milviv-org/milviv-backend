package com.milviv.domain.gamification;

import java.time.Instant;
import java.util.UUID;

/**
 * Запись истории баллов.
 *
 * @param amount       начисление > 0, списание < 0
 * @param rewardHidden награда записи сейчас скрыта; false — награды нет или она видна
 */
public record PointsEntry(
        long id,
        PointsKind kind,
        int amount,
        String reason,
        UUID taskId,
        UUID rewardId,
        boolean rewardHidden,
        Instant createdAt
) {

    public static final String HIDDEN_REWARD_REASON = "Hidden reward redeemed";

    /** Запись без следов скрытой награды: ни id, ни названия. */
    public PointsEntry masked() {
        return rewardHidden ? new PointsEntry(id, kind, amount, HIDDEN_REWARD_REASON, taskId, null, true, createdAt) : this;
    }
}
