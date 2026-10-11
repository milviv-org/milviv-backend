package com.milviv.domain.statistics;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.milviv.domain.planning.TaskReschedule;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskCompletion;

/**
 * Сводка выполненной работы за период (FR-07). Считается по фактам выполнения,
 * а не по текущим задачам: изменение или удаление задачи статистику не меняет (G-17, G-20).
 * Даты — по UTC.
 *
 * @param byDifficulty все шесть уровней, в том числе нулевые
 * @param byDay        каждый день периода, в том числе пустые
 * @param byWeekday    все семь дней недели
 */
public record CompletionSummary(
        LocalDate from,
        LocalDate to,
        Bucket total,
        Map<Difficulty, Bucket> byDifficulty,
        DeadlineStats deadlines,
        PlanStats plan,
        RescheduleStats reschedules,
        List<DayBucket> byDay,
        Map<DayOfWeek, Bucket> byWeekday
) {

    public record Bucket(int completed, long points) {
        static final Bucket EMPTY = new Bucket(0, 0);

        Bucket plus(TaskCompletion completion) {
            return new Bucket(completed + 1, points + completion.points());
        }
    }

    public record DayBucket(LocalDate date, int completed, long points) {}

    /** onTime — выполнена не позже дня срока. */
    public record DeadlineStats(int onTime, int late, int withoutDeadline) {}

    /** План против факта (QG-04): день выполнения сравнивается с плановой датой. */
    public record PlanStats(int onPlannedDay, int earlier, int later, int unplanned) {}

    /** @param tasks разных задач среди перенесённых; переносы удалённых задач сюда не входят */
    public record RescheduleStats(int count, int tasks) {}

    public static CompletionSummary calculate(LocalDate from, LocalDate to,
                                              List<TaskCompletion> completions, List<TaskReschedule> reschedules) {
        Bucket total = Bucket.EMPTY;
        Map<Difficulty, Bucket> byDifficulty = new EnumMap<>(Difficulty.class);
        Map<DayOfWeek, Bucket> byWeekday = new EnumMap<>(DayOfWeek.class);
        Map<LocalDate, Bucket> byDate = new LinkedHashMap<>();
        for (Difficulty difficulty : Difficulty.values()) {
            byDifficulty.put(difficulty, Bucket.EMPTY);
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            byWeekday.put(day, Bucket.EMPTY);
        }
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            byDate.put(date, Bucket.EMPTY);
        }

        int onTime = 0, late = 0, withoutDeadline = 0;
        int onPlannedDay = 0, earlier = 0, later = 0, unplanned = 0;

        for (TaskCompletion completion : completions) {
            LocalDate day = LocalDate.ofInstant(completion.completedAt(), ZoneOffset.UTC);
            total = total.plus(completion);
            byDifficulty.put(completion.difficulty(), byDifficulty.get(completion.difficulty()).plus(completion));
            byWeekday.put(day.getDayOfWeek(), byWeekday.get(day.getDayOfWeek()).plus(completion));
            byDate.computeIfPresent(day, (date, bucket) -> bucket.plus(completion));

            if (completion.deadline() == null) {
                withoutDeadline++;
            } else if (day.isAfter(completion.deadline())) {
                late++;
            } else {
                onTime++;
            }

            if (completion.plannedDate() == null) {
                unplanned++;
            } else if (day.isBefore(completion.plannedDate())) {
                earlier++;
            } else if (day.isAfter(completion.plannedDate())) {
                later++;
            } else {
                onPlannedDay++;
            }
        }

        List<DayBucket> byDay = new ArrayList<>();
        byDate.forEach((date, bucket) -> byDay.add(new DayBucket(date, bucket.completed(), bucket.points())));

        int rescheduledTasks = (int) reschedules.stream()
                .map(TaskReschedule::taskId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return new CompletionSummary(from, to, total, byDifficulty,
                new DeadlineStats(onTime, late, withoutDeadline),
                new PlanStats(onPlannedDay, earlier, later, unplanned),
                new RescheduleStats(reschedules.size(), rescheduledTasks),
                byDay, byWeekday);
    }
}
