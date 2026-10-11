package com.milviv.application.planning;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import com.milviv.domain.planning.Plan;

/** План для ответа клиенту. daysOfWeek = null у разового плана, planDate = null у повторяющегося. */
public record PlanView(
        UUID id,
        UUID taskId,
        String title,
        String description,
        LocalDate planDate,
        Set<DayOfWeek> daysOfWeek,
        LocalTime timeFrom,
        LocalTime timeTo,
        Instant createdAt,
        Instant updatedAt
) {

    public static PlanView from(Plan plan) {
        return new PlanView(plan.getId(), plan.getTaskId(), plan.getTitle(), plan.getDescription(), plan.getPlanDate(),
                plan.getDaysOfWeek(), plan.getTimeFrom(), plan.getTimeTo(), plan.getCreatedAt(), plan.getUpdatedAt());
    }
}
