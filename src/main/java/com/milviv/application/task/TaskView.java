package com.milviv.application.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.Task;
import com.milviv.domain.task.TaskStatus;

/** Задача для ответа клиенту. overdue вычисляется на дату запроса. */
public record TaskView(
        UUID id,
        String title,
        String description,
        Difficulty difficulty,
        int points,
        TaskStatus status,
        boolean overdue,
        LocalDate startDate,
        LocalDate deadline,
        Instant reminderAt,
        Instant completedAt,
        UUID projectId,
        UUID goalId,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static TaskView from(Task task, LocalDate today) {
        return new TaskView(task.getId(), task.getTitle(), task.getDescription(), task.getDifficulty(),
                task.getPoints(), task.getStatus(), task.isOverdue(today), task.getStartDate(), task.getDeadline(),
                task.getReminderAt(), task.getCompletedAt(), task.getProjectId(), task.getGoalId(),
                task.getVersion(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
