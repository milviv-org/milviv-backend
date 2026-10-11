package com.milviv.domain.task;

import java.util.UUID;

/** Фильтр списка задач. null в поле — без фильтра по нему. */
public record TaskFilter(TaskStatus status, UUID projectId, UUID goalId) {

    public static TaskFilter all() {
        return new TaskFilter(null, null, null);
    }
}
