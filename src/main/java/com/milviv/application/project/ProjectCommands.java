package com.milviv.application.project;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.goal.GoalRepository;
import com.milviv.domain.project.Project;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.project.ProjectRepository;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.task.TaskRepository;

/**
 * Сценарии работы с проектом. Чужой проект неотличим от несуществующего: 404.
 * Чужая цель в запросе — 400: «не найдена».
 */
public class ProjectCommands {

    public static final int MAX_PAGE_SIZE = 100;

    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final GoalRepository goals;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public ProjectCommands(ProjectRepository projects, TaskRepository tasks, GoalRepository goals,
                           TransactionManager transactionManager, Clock clock) {
        this.projects = projects;
        this.tasks = tasks;
        this.goals = goals;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public ProjectView create(UUID userId, ProjectDetails details) {
        Project project = Project.create(userId, details, clock.instant());
        transactionManager.execute(() -> {
            requireGoal(userId, details.goalId());
            projects.add(project);
        });
        return ProjectView.from(project);
    }

    public ProjectView get(UUID userId, UUID projectId) {
        return ProjectView.from(transactionManager.execute(Propagation.SUPPORTS,
                () -> projects.findById(userId, projectId).orElseThrow(ProjectCommands::notFound)));
    }

    public List<ProjectView> list(UUID userId, ProjectStatus status, UUID goalId, int limit, int offset) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return transactionManager.execute(Propagation.SUPPORTS,
                        () -> projects.findByUser(userId, status, goalId, pageSize, from))
                .stream()
                .map(ProjectView::from)
                .toList();
    }

    /**
     * Срок проверяется по задачам проекта; строка проекта заблокирована,
     * поэтому задача не может параллельно получить срок позже нового.
     *
     * @param expectedVersion версия, которую видел клиент; изменилась — 409
     */
    public ProjectView update(UUID userId, UUID projectId, ProjectDetails details, long expectedVersion) {
        Instant now = clock.instant();
        return ProjectView.from(transactionManager.execute(() -> {
            Project project = lockOrThrow(userId, projectId);
            if (project.getVersion() != expectedVersion) {
                throw new ConflictException("Проект был изменён. Обновите данные и повторите");
            }
            requireGoal(userId, details.goalId());
            LocalDate latestTaskDeadline = tasks.findLatestDeadlineInProject(userId, projectId).orElse(null);
            project.edit(details, latestTaskDeadline, now);
            projects.update(project);
            return project;
        }));
    }

    public ProjectView complete(UUID userId, UUID projectId) {
        return transition(userId, projectId, Project::complete);
    }

    public ProjectView cancel(UUID userId, UUID projectId) {
        return transition(userId, projectId, Project::cancel);
    }

    /** Задачи проекта остаются без проекта. */
    public void delete(UUID userId, UUID projectId) {
        boolean deleted = transactionManager.execute(() -> projects.delete(userId, projectId));
        if (!deleted) {
            throw notFound();
        }
    }

    private ProjectView transition(UUID userId, UUID projectId, BiConsumer<Project, Instant> change) {
        Instant now = clock.instant();
        return ProjectView.from(transactionManager.execute(() -> {
            Project project = lockOrThrow(userId, projectId);
            change.accept(project, now);
            projects.update(project);
            return project;
        }));
    }

    private Project lockOrThrow(UUID userId, UUID projectId) {
        return projects.findByIdForUpdate(userId, projectId).orElseThrow(ProjectCommands::notFound);
    }

    private void requireGoal(UUID userId, UUID goalId) {
        if (goalId != null && goals.findById(userId, goalId).isEmpty()) {
            throw new ValidationException("goalId", "Цель не найдена");
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Проект не найден");
    }
}
