package com.milviv.domain.task;

import java.util.Optional;
import java.util.UUID;

/** История выполнения задач. Записи только добавляются. */
public interface TaskCompletionRepository {

    void add(TaskCompletion completion);

    /**
     * id последнего факта выполнения задачи с блокировкой строки до конца транзакции:
     * параллельные ответы рефлексии на один факт идут по очереди.
     */
    Optional<Long> findLatestIdByTaskForUpdate(UUID userId, UUID taskId);

    /** id последнего факта выполнения задачи без блокировки. */
    Optional<Long> findLatestIdByTask(UUID userId, UUID taskId);
}
