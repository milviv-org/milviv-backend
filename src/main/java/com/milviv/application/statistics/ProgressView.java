package com.milviv.application.statistics;

import com.milviv.domain.statistics.WorkProgress;

/** Прогресс для ответа клиенту. progressPercent = null — нет неотменённых задач. */
public record ProgressView(int total, int active, int completed, int cancelled, int overdue,
                           long pointsEarned, Integer progressPercent) {

    public static ProgressView from(WorkProgress progress) {
        return new ProgressView(progress.total(), progress.active(), progress.completed(), progress.cancelled(),
                progress.overdue(), progress.pointsEarned(), progress.progressPercent());
    }
}
