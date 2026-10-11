package com.milviv.domain.project;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.shared.VersionedAggregate;
import com.milviv.domain.task.Difficulty;

/**
 * Проект — группа задач. Баллов не даёт, сложность не задаётся пользователем.
 *
 * Правила:
 * - срок проекта не раньше сроков его задач; задача без срока не ограничивает;
 * - сложность — среднее уровней неотменённых задач, округлённое; без задач — нет сложности;
 * - завершить или отменить можно только активный проект; на задачи это не влияет.
 */
public class Project extends VersionedAggregate {

    private final UUID id;
    private final UUID userId;
    private final Instant createdAt;

    private UUID goalId;
    private String title;
    private String description;
    private Difficulty calculatedDifficulty;
    private ProjectStatus status;
    private LocalDate deadline;
    private Instant completedAt;
    private Instant updatedAt;

    /** Восстановление из БД. Вызывается только репозиторием. */
    public Project(UUID id, UUID userId, UUID goalId, String title, String description,
                   Difficulty calculatedDifficulty, ProjectStatus status, LocalDate deadline,
                   Instant completedAt, Instant createdAt, Instant updatedAt, long version) {
        super(version);
        this.id = id;
        this.userId = userId;
        this.goalId = goalId;
        this.title = title;
        this.description = description;
        this.calculatedDifficulty = calculatedDifficulty;
        this.status = status;
        this.deadline = deadline;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Новый проект пуст — задачи к нему привязываются потом, поэтому сроки не проверяются. */
    public static Project create(UUID userId, ProjectDetails details, Instant now) {
        Project project = new Project(UUID.randomUUID(), userId, null, null, null, null,
                ProjectStatus.ACTIVE, null, null, now, now, 0L);
        project.apply(details, now);
        return project;
    }

    /** @param latestTaskDeadline самый поздний срок среди задач проекта; null — ни у одной нет срока */
    public void edit(ProjectDetails details, LocalDate latestTaskDeadline, Instant now) {
        if (details.deadline() != null && latestTaskDeadline != null && details.deadline().isBefore(latestTaskDeadline)) {
            throw new ValidationException("deadline",
                    "Срок проекта не может быть раньше сроков его задач (" + latestTaskDeadline + "). Сначала перенесите задачи");
        }
        apply(details, now);
    }

    /** Проверка задачи, которую кладут в этот проект или которой меняют срок. */
    public void requireTaskDeadlineFits(LocalDate taskDeadline) {
        if (deadline != null && taskDeadline != null && taskDeadline.isAfter(deadline)) {
            throw new ValidationException("deadline",
                    "Срок задачи не может быть позже срока проекта (" + deadline + ")");
        }
    }

    public void complete(Instant now) {
        requireActive("завершить");
        this.status = ProjectStatus.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        requireActive("отменить");
        this.status = ProjectStatus.CANCELLED;
        this.updatedAt = now;
    }

    /**
     * Сложность по уровням неотменённых задач: среднее по порядку уровней, половина округляется вверх.
     *
     * @return null, если задач нет
     */
    public static Difficulty calculateDifficulty(Collection<Difficulty> taskDifficulties) {
        if (taskDifficulties.isEmpty()) {
            return null;
        }
        int sum = taskDifficulties.stream().mapToInt(Difficulty::ordinal).sum();
        int index = BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(taskDifficulties.size()), 0, RoundingMode.HALF_UP)
                .intValue();
        return Difficulty.values()[index];
    }

    private void apply(ProjectDetails details, Instant now) {
        this.goalId = details.goalId();
        this.title = details.title().trim();
        this.description = details.description();
        this.deadline = details.deadline();
        this.updatedAt = now;
    }

    private void requireActive(String action) {
        if (status != ProjectStatus.ACTIVE) {
            throw new ConflictException("Нельзя " + action + " проект в статусе " + status);
        }
    }

    public UUID getId()                         { return id; }
    public UUID getUserId()                     { return userId; }
    public UUID getGoalId()                     { return goalId; }
    public String getTitle()                    { return title; }
    public String getDescription()              { return description; }
    public Difficulty getCalculatedDifficulty() { return calculatedDifficulty; }
    public ProjectStatus getStatus()            { return status; }
    public LocalDate getDeadline()              { return deadline; }
    public Instant getCompletedAt()             { return completedAt; }
    public Instant getCreatedAt()               { return createdAt; }
    public Instant getUpdatedAt()               { return updatedAt; }
}
