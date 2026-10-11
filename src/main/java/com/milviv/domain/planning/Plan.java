package com.milviv.domain.planning;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.milviv.domain.exception.ValidationException;

/**
 * План — когда выполнять работу: на дату или по дням недели, с интервалом времени.
 *
 * Правила:
 * - задаётся ровно одно: дата или непустой набор дней недели;
 * - конец позже начала;
 * - повторяющийся план действует со дня создания (по дате UTC);
 * - смена даты разового плана той же задачи — перенос, он попадает в историю (QG-04).
 */
public class Plan {

    private final UUID id;
    private final UUID userId;
    private final Instant createdAt;

    private UUID taskId;
    private String title;
    private String description;
    private LocalDate planDate;
    private Set<DayOfWeek> daysOfWeek;
    private LocalTime timeFrom;
    private LocalTime timeTo;
    private Instant updatedAt;

    /** Восстановление из БД. Вызывается только репозиторием. */
    public Plan(UUID id, UUID userId, UUID taskId, String title, String description, LocalDate planDate,
                Set<DayOfWeek> daysOfWeek, LocalTime timeFrom, LocalTime timeTo, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.taskId = taskId;
        this.title = title;
        this.description = description;
        this.planDate = planDate;
        this.daysOfWeek = copy(daysOfWeek);
        this.timeFrom = timeFrom;
        this.timeTo = timeTo;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Plan create(UUID userId, PlanDetails details, Instant now) {
        Plan plan = new Plan(UUID.randomUUID(), userId, null, null, null, null, null, null, null, now, now);
        plan.apply(details, now);
        return plan;
    }

    /** @return перенос, если у той же задачи сменилась дата разового плана */
    public Optional<TaskReschedule> edit(PlanDetails details, Instant now) {
        UUID previousTask = taskId;
        LocalDate previousDate = planDate;
        apply(details, now);

        boolean rescheduled = previousTask != null && previousTask.equals(taskId)
                && previousDate != null && planDate != null && !previousDate.equals(planDate);
        return rescheduled
                ? Optional.of(new TaskReschedule(userId, taskId, previousDate, planDate, now))
                : Optional.empty();
    }

    public boolean isRecurring() {
        return daysOfWeek != null;
    }

    public boolean occursOn(LocalDate date) {
        if (!isRecurring()) {
            return planDate.equals(date);
        }
        return daysOfWeek.contains(date.getDayOfWeek())
                && !date.isBefore(LocalDate.ofInstant(createdAt, ZoneOffset.UTC));
    }

    private void apply(PlanDetails details, Instant now) {
        boolean hasDays = details.daysOfWeek() != null && !details.daysOfWeek().isEmpty();
        if ((details.planDate() != null) == hasDays) {
            throw new ValidationException("planDate", "Укажите либо дату, либо дни недели");
        }
        if (!details.timeTo().isAfter(details.timeFrom())) {
            throw new ValidationException("timeTo", "Конец должен быть позже начала");
        }
        this.taskId = details.taskId();
        this.title = details.title().trim();
        this.description = details.description();
        this.planDate = details.planDate();
        this.daysOfWeek = hasDays ? copy(details.daysOfWeek()) : null;
        this.timeFrom = details.timeFrom();
        this.timeTo = details.timeTo();
        this.updatedAt = now;
    }

    private static Set<DayOfWeek> copy(Set<DayOfWeek> days) {
        return days == null || days.isEmpty() ? null : Collections.unmodifiableSet(EnumSet.copyOf(days));
    }

    public UUID getId()                  { return id; }
    public UUID getUserId()              { return userId; }
    public UUID getTaskId()              { return taskId; }
    public String getTitle()             { return title; }
    public String getDescription()       { return description; }
    public LocalDate getPlanDate()       { return planDate; }
    public Set<DayOfWeek> getDaysOfWeek() { return daysOfWeek; }
    public LocalTime getTimeFrom()       { return timeFrom; }
    public LocalTime getTimeTo()         { return timeTo; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }
}
