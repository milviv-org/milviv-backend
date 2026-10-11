package com.milviv.domain.task;

/** История выполнения задач. Записи только добавляются. */
public interface TaskCompletionRepository {

    void add(TaskCompletion completion);
}
