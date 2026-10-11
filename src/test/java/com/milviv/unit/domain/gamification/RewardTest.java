package com.milviv.unit.domain.gamification;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.gamification.PointsEntry;
import com.milviv.domain.gamification.PointsKind;
import com.milviv.domain.gamification.Reward;
import com.milviv.domain.gamification.RewardDetails;

class RewardTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Test
    void cost_shouldBeWithinLimits() {
        UUID user = UUID.randomUUID();
        assertThatThrownBy(() -> Reward.create(user, new RewardDetails("x", null, 0, false), NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Reward.create(user, new RewardDetails("x", null, Reward.MAX_COST + 1, false), NOW))
                .isInstanceOf(ValidationException.class);
        assertThat(Reward.create(user, new RewardDetails("x", null, Reward.MAX_COST, false), NOW).getCost())
                .isEqualTo(Reward.MAX_COST);
    }

    @Test
    void redemptionReason_shouldNotRevealHiddenTitle() {
        UUID user = UUID.randomUUID();
        assertThat(Reward.create(user, new RewardDetails("Кино", null, 1, false), NOW).redemptionReason())
                .isEqualTo("Reward redeemed: Кино");
        assertThat(Reward.create(user, new RewardDetails("Тайное", null, 1, true), NOW).redemptionReason())
                .isEqualTo(PointsEntry.HIDDEN_REWARD_REASON);
    }

    @Test
    void masked_shouldDropRewardOfHiddenEntryOnly() {
        UUID rewardId = UUID.randomUUID();
        PointsEntry hidden = new PointsEntry(1, PointsKind.REWARD_REDEEMED, -5, "Reward redeemed: Тайное", null, rewardId, true, NOW);
        PointsEntry visible = new PointsEntry(2, PointsKind.REWARD_REDEEMED, -5, "Reward redeemed: Кино", null, rewardId, false, NOW);

        assertThat(hidden.masked().rewardId()).isNull();
        assertThat(hidden.masked().reason()).isEqualTo(PointsEntry.HIDDEN_REWARD_REASON);
        assertThat(visible.masked()).isEqualTo(visible);
    }
}
