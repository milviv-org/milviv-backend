package com.milviv.domain.gamification;

import java.time.Instant;
import java.util.UUID;

/**
 * История операций с баллами — источник правды для баланса (AD-16). Записи только добавляются.
 * Одна задача — одно начисление: повтор нарушит уникальный индекс в БД (QG-03).
 */
public interface PointsHistoryRepository {

    void recordTaskCompleted(UUID userId, UUID taskId, int points, String reason, Instant at);
}
