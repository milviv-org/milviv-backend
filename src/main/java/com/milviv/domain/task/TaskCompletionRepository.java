package com.milviv.domain.task;

import java.time.Instant;
import java.util.List;
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

    /** Факты выполнения в [from, toExclusive), в том числе удалённых задач — для статистики. */
    List<TaskCompletion> findBetween(UUID userId, Instant from, Instant toExclusive);

    /** id последнего факта выполнения задачи без блокировки. */
    Optional<Long> findLatestIdByTask(UUID userId, UUID taskId);
}
