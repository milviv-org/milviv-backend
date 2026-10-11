package com.milviv.domain.goal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Хранилище целей. Каждый метод фильтрует по userId (AD-14). */
public interface GoalRepository {

    void add(Goal goal);

    Optional<Goal> findById(UUID userId, UUID goalId);

    /** Цели пользователя, новые первыми. */
    List<Goal> findByUser(UUID userId, int limit, int offset);

    /** Версия не совпала — OptimisticLockException. */
    void update(Goal goal);

    /** Проекты и задачи цели отвязываются (ON DELETE SET NULL). @return true, если цель была и удалена */
    boolean delete(UUID userId, UUID goalId);
}
