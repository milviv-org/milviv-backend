package com.milviv.domain.project;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.task.Difficulty;

/** Хранилище проектов. Каждый метод фильтрует по userId (AD-14). */
public interface ProjectRepository {

    void add(Project project);

    Optional<Project> findById(UUID userId, UUID projectId);

    /**
     * То же с блокировкой строки до конца транзакции (SELECT … FOR UPDATE).
     * Изменения задач одного проекта и самого проекта идут по очереди — иначе
     * параллельные правки нарушат правило сроков или оставят неверную сложность.
     */
    Optional<Project> findByIdForUpdate(UUID userId, UUID projectId);

    /** Проекты пользователя, новые первыми. status, goalId = null — без фильтра. */
    List<Project> findByUser(UUID userId, ProjectStatus status, UUID goalId, int limit, int offset);

    /** Активные проекты со сроком в [from, to] — для календаря. */
    List<Project> findActiveWithDeadlineBetween(UUID userId, LocalDate from, LocalDate to);

    /** Редактируемые поля и статус. Версия не совпала — OptimisticLockException. Сложность не трогает. */
    void update(Project project);

    /**
     * Записать вычисленную сложность. Версию не меняет: это не правка пользователя,
     * и открытая у клиента форма проекта не должна устаревать из-за изменений задач.
     */
    void updateCalculatedDifficulty(UUID userId, UUID projectId, Difficulty difficulty);

    /** Задачи проекта отвязываются (ON DELETE SET NULL). @return true, если проект был и удалён */
    boolean delete(UUID userId, UUID projectId);
}
