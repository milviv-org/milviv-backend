package com.milviv.domain.ai;

import java.time.Instant;
import java.util.List;

/**
 * Решение пользователя по карточке — доказательство согласия, хранится бессрочно (§13).
 *
 * @param approved подтверждённые itemId; пусто — отклонено всё
 */
public record AiDecision(List<String> approved, Instant decidedAt) {

    public AiDecision {
        approved = List.copyOf(approved);
    }
}
