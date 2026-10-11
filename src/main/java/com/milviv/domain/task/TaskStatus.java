package com.milviv.domain.task;

/**
 * Статус задачи. Просрочка — не статус: задача просрочена,
 * если она ACTIVE и срок уже прошёл (Task.isOverdue).
 */
public enum TaskStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED
}
