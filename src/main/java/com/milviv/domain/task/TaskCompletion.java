package com.milviv.domain.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Факт выполнения задачи для аналитики (QG-04). Записывается один раз и не меняется;
 * остаётся после удаления задачи.
 *
 * @param plannedDate на какой день задача была запланирована; null — не планировалась
 */
public record TaskCompletion(
        UUID userId,
        UUID taskId,
        Difficulty difficulty,
        int points,
        LocalDate plannedDate,
        LocalDate deadline,
        Instant completedAt
) {

    public static TaskCompletion of(Task task, LocalDate plannedDate) {
        return new TaskCompletion(task.getUserId(), task.getId(), task.getDifficulty(), task.getPoints(),
                plannedDate, task.getDeadline(), task.getCompletedAt());
    }
}
