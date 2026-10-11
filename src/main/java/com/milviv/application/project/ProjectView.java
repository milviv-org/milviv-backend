package com.milviv.application.project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.milviv.domain.project.Project;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.task.Difficulty;

/** Проект для ответа клиенту. */
public record ProjectView(
        UUID id,
        UUID goalId,
        String title,
        String description,
        Difficulty calculatedDifficulty,
        ProjectStatus status,
        LocalDate deadline,
        Instant completedAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static ProjectView from(Project project) {
        return new ProjectView(project.getId(), project.getGoalId(), project.getTitle(), project.getDescription(),
                project.getCalculatedDifficulty(), project.getStatus(), project.getDeadline(), project.getCompletedAt(),
                project.getVersion(), project.getCreatedAt(), project.getUpdatedAt());
    }
}
