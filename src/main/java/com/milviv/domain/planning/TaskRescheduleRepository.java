package com.milviv.domain.planning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** История переносов. Только добавление. */
public interface TaskRescheduleRepository {

    void add(TaskReschedule reschedule);

    /** Переносы, сделанные в [from, toExclusive) — для статистики. */
    List<TaskReschedule> findBetween(UUID userId, Instant from, Instant toExclusive);
}
