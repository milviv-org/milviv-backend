package com.milviv.domain.planning;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Редактируемые поля плана. Задаётся ровно одно: planDate или daysOfWeek.
 *
 * @param taskId     задача; null — план без задачи
 * @param planDate   дата разового плана
 * @param daysOfWeek дни недели повторяющегося плана
 */
public record PlanDetails(
        UUID taskId,
        String title,
        String description,
        LocalDate planDate,
        Set<DayOfWeek> daysOfWeek,
        LocalTime timeFrom,
        LocalTime timeTo
) {}
