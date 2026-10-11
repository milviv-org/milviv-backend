package com.milviv.application.ai.contract;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskStatus;

/**
 * Один раунд данных: что AI попросил и что сервер ответил (§6.3). Уходит в следующий вызов AI
 * как размеченные данные, не инструкции. Ни одного ID — только дескрипторы (принцип 8).
 *
 * @param round с 1
 */
public record DataRound(int round, String reason, List<DataResult> results, List<RejectedRequest> rejected) {

    /** @param truncated элементов больше, чем отдано (лимит элементов или токенов) */
    public record DataResult(String query, boolean truncated, List<DataItem> items) {}

    /**
     * @param index номер запроса в раунде, с 0
     * @param code  UNKNOWN_HANDLE, CONFLICTING_PARAMS, PERIOD_REQUIRED, PERIOD_TOO_LONG, INVALID_PERIOD
     */
    public record RejectedRequest(int index, String query, String code) {}

    /** Проекции каталога. Ссылки на цели, проекты, задачи — дескрипторы. */
    public sealed interface DataItem {}

    public record GoalItem(String handle, String title, String description) implements DataItem {}

    public record ProjectItem(String handle, String title, String description, ProjectStatus status,
                              LocalDate deadline, String goal) implements DataItem {}

    public record TaskItem(String handle, String title, String description, TaskStatus status, Difficulty difficulty,
                           int points, LocalDate startDate, LocalDate deadline, Instant completedAt,
                           String project, String goal) implements DataItem {}

    public record RewardItem(String handle, String title, String description, int cost) implements DataItem {}

    /** @param date у разового плана; daysOfWeek — у повторяющегося */
    public record PlanItem(LocalDate date, Set<DayOfWeek> daysOfWeek, LocalTime timeFrom, LocalTime timeTo,
                           String title, String task) implements DataItem {}

    public record ReflectionItem(String task, Instant answeredAt, String question, String answer) implements DataItem {}
}
