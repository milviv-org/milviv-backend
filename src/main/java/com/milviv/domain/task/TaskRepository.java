package com.milviv.domain.task;

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

    /** Задачи пользователя, новые первыми. status = null — все статусы. */
    List<Task> findByUser(UUID userId, TaskStatus status, int limit, int offset);

    /** Сохранить изменения. Версия не совпала — OptimisticLockException. */
    void update(Task task);

    /** @return true, если задача была и удалена */
    boolean delete(UUID userId, UUID taskId);
}
