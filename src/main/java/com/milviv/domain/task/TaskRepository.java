package com.milviv.domain.task;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище задач. Каждый метод принимает userId и фильтрует по нему в запросе к БД:
 * чужую задачу нельзя ни прочитать, ни изменить (AD-14).
 */
public interface TaskRepository {

    void add(Task task);

    Optional<Task> findById(UUID userId, UUID taskId);

    /** Задачи пользователя, новые первыми. */
    List<Task> findByUser(UUID userId, TaskFilter filter, int limit, int offset);

    /** Сложности неотменённых задач проекта — для вычисления сложности проекта. */
    List<Difficulty> findNotCancelledDifficultiesInProject(UUID userId, UUID projectId);

    /** Самый поздний срок среди задач проекта; пусто — ни у одной нет срока. */
    Optional<LocalDate> findLatestDeadlineInProject(UUID userId, UUID projectId);

    /** Сохранить изменения. Версия не совпала — OptimisticLockException. */
    void update(Task task);

    /** @return true, если задача была и удалена */
    boolean delete(UUID userId, UUID taskId);
}
