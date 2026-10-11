package com.milviv.domain.task;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Редактируемые поля задачи.
 *
 * @param points баллы; null — по сложности
 */
public record TaskDetails(
        String title,
        String description,
        Difficulty difficulty,
        Integer points,
        LocalDate startDate,
        LocalDate deadline,
        Instant reminderAt
) {}
