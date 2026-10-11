package com.milviv.application.task;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.goal.GoalRepository;
import com.milviv.domain.project.Project;
import com.milviv.domain.project.ProjectRepository;
import com.milviv.domain.task.TaskDetails;
import com.milviv.domain.task.TaskRepository;

/**
 * Связи задачи с проектом и целью. Вызывается внутри транзакции сценария.
 *
 * Порядок в сценарии: lock → validate → изменить задачу → refreshDifficulty.
 */
class TaskLinks {

    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final GoalRepository goals;

    TaskLinks(TaskRepository tasks, ProjectRepository projects, GoalRepository goals) {
        this.tasks = tasks;
        this.projects = projects;
        this.goals = goals;
    }

    /**
     * Блокирует строки затронутых проектов до конца транзакции.
     * Порядок по id одинаков у всех транзакций — взаимных блокировок нет.
     */
    void lock(UUID userId, UUID... projectIds) {
        Stream.of(projectIds).filter(Objects::nonNull).distinct().sorted()
                .forEach(projectId -> projects.findByIdForUpdate(userId, projectId));
    }

    /** Проект и цель существуют у этого пользователя, срок задачи не позже срока проекта. */
    void validate(UUID userId, TaskDetails details) {
        if (details.projectId() != null) {
            Project project = projects.findById(userId, details.projectId())
                    .orElseThrow(() -> new ValidationException("projectId", "Проект не найден"));
            project.requireTaskDeadlineFits(details.deadline());
        }
        if (details.goalId() != null && goals.findById(userId, details.goalId()).isEmpty()) {
            throw new ValidationException("goalId", "Цель не найдена");
        }
    }

    /** Пересчитать сложность проектов после изменения их задач. */
    void refreshDifficulty(UUID userId, UUID... projectIds) {
        Stream.of(projectIds).filter(Objects::nonNull).distinct().forEach(projectId ->
                projects.updateCalculatedDifficulty(userId, projectId,
                        Project.calculateDifficulty(tasks.findNotCancelledDifficultiesInProject(userId, projectId))));
    }
}
