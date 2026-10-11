package com.milviv.domain.task;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Фильтр списка задач. null в поле — без фильтра по нему.
 *
 * @param unassigned true — только задачи без проекта и без цели
 * @param from       вместе с to — срок или дата выполнения (UTC) в периоде [from, to]
 */
public record TaskFilter(TaskStatus status, UUID projectId, UUID goalId, boolean unassigned, LocalDate from, LocalDate to) {

    public TaskFilter(TaskStatus status, UUID projectId, UUID goalId) {
        this(status, projectId, goalId, false, null, null);
    }

    public static TaskFilter all() {
        return new TaskFilter(null, null, null);
    }

    public boolean hasPeriod() {
        return from != null && to != null;
    }
}
