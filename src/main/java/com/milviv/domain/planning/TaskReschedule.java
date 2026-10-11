package com.milviv.domain.planning;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Перенос плановой даты задачи (QG-04). Записывается один раз и не меняется;
 * остаётся после удаления задачи.
 */
public record TaskReschedule(UUID userId, UUID taskId, LocalDate fromDate, LocalDate toDate, Instant rescheduledAt) {}
