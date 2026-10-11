package com.milviv.domain.gamification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище наград. Каждый метод фильтрует по userId (AD-14).
 * Видимость скрытых наград решает прикладной слой — репозиторий возвращает их как есть.
 */
public interface RewardRepository {

    void add(Reward reward);

    Optional<Reward> findById(UUID userId, UUID rewardId);

    /** Награды, дешёвые первыми. includeHidden = false — только видимые. */
    List<Reward> findByUser(UUID userId, boolean includeHidden, int limit, int offset);

    /** Версия не совпала — OptimisticLockException. */
    void update(Reward reward);

    /** Записи истории баллов остаются (reward_id → NULL). @return true, если награда была и удалена */
    boolean delete(UUID userId, UUID rewardId);
}
