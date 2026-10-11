package com.milviv.unit.domain.statistics;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.milviv.domain.planning.TaskReschedule;
import com.milviv.domain.statistics.CompletionSummary;
import com.milviv.domain.statistics.CompletionSummary.Bucket;
import com.milviv.domain.statistics.WorkProgress;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskCompletion;

class CompletionSummaryTest {

    // понедельник … среда
    private static final LocalDate MON = LocalDate.parse("2026-10-12");
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private final UUID user = UUID.randomUUID();

    private TaskCompletion done(Difficulty difficulty, int points, LocalDate on, LocalDate deadline, LocalDate planned) {
        return new TaskCompletion(user, UUID.randomUUID(), difficulty, points, planned, deadline,
                Instant.parse(on + "T23:30:00Z"));
    }

    @Test
    void emptyPeriod_shouldListAllDaysLevelsAndWeekdaysWithZeros() {
        CompletionSummary summary = CompletionSummary.calculate(MON, WED, List.of(), List.of());

        assertThat(summary.total()).isEqualTo(new Bucket(0, 0));
        assertThat(summary.byDay()).hasSize(3).allMatch(day -> day.completed() == 0);
        assertThat(summary.byDifficulty()).hasSize(6);
        assertThat(summary.byWeekday()).hasSize(7);
    }

    @Test
    void shouldCountTotalsDifficultiesDaysAndWeekdays() {
        CompletionSummary summary = CompletionSummary.calculate(MON, WED, List.of(
                done(Difficulty.NOVICE, 2, MON, null, null),
                done(Difficulty.EXPERT, 8, MON, null, null),
                done(Difficulty.EXPERT, 100, WED, null, null)), List.of());

        assertThat(summary.total()).isEqualTo(new Bucket(3, 110));
        assertThat(summary.byDifficulty().get(Difficulty.EXPERT)).isEqualTo(new Bucket(2, 108));
        assertThat(summary.byDifficulty().get(Difficulty.MASTER)).isEqualTo(new Bucket(0, 0));
        assertThat(summary.byDay()).extracting(CompletionSummary.DayBucket::completed).containsExactly(2, 0, 1);
        assertThat(summary.byWeekday().get(DayOfWeek.MONDAY)).isEqualTo(new Bucket(2, 10));
        assertThat(summary.byWeekday().get(DayOfWeek.WEDNESDAY)).isEqualTo(new Bucket(1, 100));
    }

    @Test
    void shouldCompareWithDeadlineAndPlan_byUtcDay() {
        CompletionSummary summary = CompletionSummary.calculate(MON, WED, List.of(
                // 23:30 UTC того же дня, что и срок, — вовремя
                done(Difficulty.NOVICE, 2, TUE, TUE, TUE),
                done(Difficulty.NOVICE, 2, WED, TUE, TUE),
                done(Difficulty.NOVICE, 2, MON, null, TUE),
                done(Difficulty.NOVICE, 2, MON, WED, null)), List.of());

        assertThat(summary.deadlines()).isEqualTo(new CompletionSummary.DeadlineStats(2, 1, 1));
        assertThat(summary.plan()).isEqualTo(new CompletionSummary.PlanStats(1, 1, 1, 1));
    }

    @Test
    void reschedules_shouldCountMovesAndDistinctLiveTasks() {
        UUID task = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-12T10:00:00Z");
        CompletionSummary summary = CompletionSummary.calculate(MON, WED, List.of(), List.of(
                new TaskReschedule(user, task, MON, TUE, at),
                new TaskReschedule(user, task, TUE, WED, at),
                new TaskReschedule(user, null, MON, WED, at)));

        assertThat(summary.reschedules()).isEqualTo(new CompletionSummary.RescheduleStats(3, 1));
    }

    @Test
    void progressPercent_ignoresCancelled_andIsNullWithoutWork() {
        assertThat(new WorkProgress(4, 1, 2, 1, 0, 0).progressPercent()).isEqualTo(66);
        assertThat(new WorkProgress(2, 0, 0, 2, 0, 0).progressPercent()).isNull();
        assertThat(new WorkProgress(0, 0, 0, 0, 0, 0).progressPercent()).isNull();
    }
}
