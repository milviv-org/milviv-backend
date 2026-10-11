package com.milviv.domain.statistics;

/**
 * Состояние задач проекта или цели на текущий момент.
 *
 * @param overdue      активные с прошедшим сроком (по дате UTC)
 * @param pointsEarned начислено за выполненные задачи — по фактам выполнения, а не по текущим баллам задач
 */
public record WorkProgress(int total, int active, int completed, int cancelled, int overdue, long pointsEarned) {

    /**
     * Доля выполненных среди неотменённых, проценты с округлением вниз.
     *
     * @return null, если неотменённых задач нет
     */
    public Integer progressPercent() {
        int relevant = total - cancelled;
        return relevant == 0 ? null : completed * 100 / relevant;
    }
}
