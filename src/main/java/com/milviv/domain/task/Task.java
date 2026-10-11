package com.milviv.domain.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.shared.VersionedAggregate;

/**
 * Задача — конкретное действие, за выполнение которого начисляются баллы.
 *
 * Правила:
 * - баллы 1..1000; не указаны — берутся по сложности;
 * - дату начала и срок можно ставить в прошлом, но начало не позже срока;
 * - выполнить или отменить можно только активную задачу;
 * - выполненную задачу можно редактировать — уже начисленные баллы не меняются;
 * - срок не позже срока проекта — проверяет Project.requireTaskDeadlineFits,
 *   проект загружает прикладной сценарий.
 */
public class Task extends VersionedAggregate {

    public static final int MIN_POINTS = 1;
    public static final int MAX_POINTS = 1000;

    private final UUID id;
    private final UUID userId;
    private final Instant createdAt;

    private UUID projectId;
    private UUID goalId;
    private String title;
    private String description;
    private Difficulty difficulty;
    private int points;
    private TaskStatus status;
    private LocalDate startDate;
    private LocalDate deadline;
    private Instant reminderAt;
    private Instant completedAt;
    private Instant updatedAt;

    /** Восстановление из БД. Вызывается только репозиторием. */
    public Task(UUID id, UUID userId, UUID projectId, UUID goalId, String title, String description,
                Difficulty difficulty, int points, TaskStatus status, LocalDate startDate, LocalDate deadline,
                Instant reminderAt, Instant completedAt, Instant createdAt, Instant updatedAt, long version) {
        super(version);
        this.id = id;
        this.userId = userId;
        this.projectId = projectId;
        this.goalId = goalId;
        this.title = title;
        this.description = description;
        this.difficulty = difficulty;
        this.points = points;
        this.status = status;
        this.startDate = startDate;
        this.deadline = deadline;
        this.reminderAt = reminderAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Task create(UUID userId, TaskDetails details, Instant now) {
        Task task = new Task(UUID.randomUUID(), userId, null, null, null, null, null, 0,
                TaskStatus.ACTIVE, null, null, null, null, now, now, 0L);
        task.apply(details, now);
        return task;
    }

    /** Изменение полей. Статус и даты выполнения не меняются. */
    public void edit(TaskDetails details, Instant now) {
        apply(details, now);
    }

    public void complete(Instant now) {
        requireActive("выполнить");
        this.status = TaskStatus.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        requireActive("отменить");
        this.status = TaskStatus.CANCELLED;
        this.updatedAt = now;
    }

    public boolean isOverdue(LocalDate today) {
        return status == TaskStatus.ACTIVE && deadline != null && deadline.isBefore(today);
    }

    private void apply(TaskDetails details, Instant now) {
        int resolvedPoints = details.points() != null ? details.points() : details.difficulty().defaultPoints();
        if (resolvedPoints < MIN_POINTS || resolvedPoints > MAX_POINTS) {
            throw new ValidationException("points", "Баллы должны быть от " + MIN_POINTS + " до " + MAX_POINTS);
        }
        if (details.startDate() != null && details.deadline() != null && details.startDate().isAfter(details.deadline())) {
            throw new ValidationException("startDate", "Дата начала не может быть позже срока");
        }
        this.projectId = details.projectId();
        this.goalId = details.goalId();
        this.title = details.title().trim();
        this.description = details.description();
        this.difficulty = details.difficulty();
        this.points = resolvedPoints;
        this.startDate = details.startDate();
        this.deadline = details.deadline();
        this.reminderAt = details.reminderAt();
        this.updatedAt = now;
    }

    private void requireActive(String action) {
        if (status != TaskStatus.ACTIVE) {
            throw new ConflictException("Нельзя " + action + " задачу в статусе " + status);
        }
    }

    public UUID getId()               { return id; }
    public UUID getUserId()           { return userId; }
    public UUID getProjectId()        { return projectId; }
    public UUID getGoalId()           { return goalId; }
    public String getTitle()          { return title; }
    public String getDescription()    { return description; }
    public Difficulty getDifficulty() { return difficulty; }
    public int getPoints()            { return points; }
    public TaskStatus getStatus()     { return status; }
    public LocalDate getStartDate()   { return startDate; }
    public LocalDate getDeadline()    { return deadline; }
    public Instant getReminderAt()    { return reminderAt; }
    public Instant getCompletedAt()   { return completedAt; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getUpdatedAt()     { return updatedAt; }
}
