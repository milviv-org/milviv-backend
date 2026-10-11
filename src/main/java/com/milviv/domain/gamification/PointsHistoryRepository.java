package com.milviv.domain.gamification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * История операций с баллами — источник правды для баланса (AD-16). Записи только добавляются.
 * Одна задача — одно начисление: повтор нарушит уникальный индекс в БД (QG-03).
 */
public interface PointsHistoryRepository {

    void recordTaskCompleted(UUID userId, UUID taskId, int points, String reason, Instant at);

    /** Списание: в истории сохраняется -cost. */
    void recordRewardRedeemed(UUID userId, UUID rewardId, int cost, String reason, Instant at);

    /**
     * Блокирует баланс пользователя до конца транзакции. Списания одного пользователя идут
     * по очереди — иначе два параллельных получения наград проверят один и тот же баланс
     * и уведут его в минус (QG-03).
     */
    void lockBalance(UUID userId);

    /** Сумма всех записей. */
    long balance(UUID userId);

    /** Записи пользователя, новые первыми. */
    List<PointsEntry> findByUser(UUID userId, int limit, int offset);
}
