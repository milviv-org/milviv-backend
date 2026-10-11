package com.milviv.domain.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Редактируемые поля задачи.
 *
 * @param points    баллы; null — по сложности
 * @param projectId проект; null — без проекта
 * @param goalId    цель; null — без цели
 */
public record TaskDetails(
        String title,
        String description,
        Difficulty difficulty,
        Integer points,
        LocalDate startDate,
        LocalDate deadline,
        Instant reminderAt,
        UUID projectId,
        UUID goalId
) {}
