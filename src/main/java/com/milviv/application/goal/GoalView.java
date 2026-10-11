package com.milviv.application.goal;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.goal.Goal;

/** Цель для ответа клиенту. */
public record GoalView(
        UUID id,
        String title,
        String description,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static GoalView from(Goal goal) {
        return new GoalView(goal.getId(), goal.getTitle(), goal.getDescription(),
                goal.getVersion(), goal.getCreatedAt(), goal.getUpdatedAt());
    }
}
