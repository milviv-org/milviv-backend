package com.milviv.application.gamification;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.gamification.PointsEntry;
import com.milviv.domain.gamification.PointsKind;

/** Запись истории баллов для ответа клиенту. */
public record PointsEntryView(
        long id,
        PointsKind kind,
        int amount,
        String reason,
        UUID taskId,
        UUID rewardId,
        Instant createdAt
) {

    public static PointsEntryView from(PointsEntry entry) {
        return new PointsEntryView(entry.id(), entry.kind(), entry.amount(), entry.reason(),
                entry.taskId(), entry.rewardId(), entry.createdAt());
    }
}
