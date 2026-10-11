package com.milviv.domain.sound;

/**
 * События задач, для которых пользователь выбирает звук.
 * Пользовательская документация список не задаёт — набор предложен по существующим событиям задач.
 */
public enum SoundEvent {
    TASK_CREATED,
    TASK_COMPLETED,
    TASK_CANCELLED,
    TASK_REMINDER,
    TASK_OVERDUE
}
