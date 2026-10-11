package com.milviv.application.gamification;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.gamification.Reward;

/** Награда для ответа клиенту. */
public record RewardView(
        UUID id,
        String title,
        String description,
        int cost,
        boolean hidden,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static RewardView from(Reward reward) {
        return new RewardView(reward.getId(), reward.getTitle(), reward.getDescription(), reward.getCost(),
                reward.isHidden(), reward.getVersion(), reward.getCreatedAt(), reward.getUpdatedAt());
    }
}
