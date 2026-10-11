package com.milviv.domain.planning;

/** История переносов. Только добавление. */
public interface TaskRescheduleRepository {

    void add(TaskReschedule reschedule);
}
