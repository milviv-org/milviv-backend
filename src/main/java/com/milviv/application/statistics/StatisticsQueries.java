package com.milviv.application.statistics;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.planning.TaskRescheduleRepository;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.statistics.CompletionSummary;
import com.milviv.domain.statistics.StatisticsRepository;
import com.milviv.domain.task.TaskCompletionRepository;

/** Статистика (FR-07). Ничего не хранит — всё вычисляется по истории и текущим задачам. */
public class StatisticsQueries {

    public static final int MAX_PAGE_SIZE = 100;

    private final TaskCompletionRepository completions;
    private final TaskRescheduleRepository reschedules;
    private final StatisticsRepository statistics;
    private final StatisticsPolicy policy;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public StatisticsQueries(TaskCompletionRepository completions, TaskRescheduleRepository reschedules,
                             StatisticsRepository statistics, StatisticsPolicy policy,
                             TransactionManager transactionManager, Clock clock) {
        this.completions = completions;
        this.reschedules = reschedules;
        this.statistics = statistics;
        this.policy = policy;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public record ProjectProgressView(UUID projectId, String title, ProjectStatus status, LocalDate deadline,
                                      ProgressView progress) {}

    public record GoalProgressView(UUID goalId, String title, int projects, ProgressView progress) {}

    public CompletionSummary summary(UUID userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ValidationException("to", "Конец периода раньше начала");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > policy.maxDays()) {
            throw new ValidationException("to", "Период не длиннее " + policy.maxDays() + " дней");
        }
        var start = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        var end = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        // одна транзакция только для чтения — выполнения и переносы согласованы между собой
        return transactionManager.execute(Propagation.SUPPORTS, () -> CompletionSummary.calculate(from, to,
                completions.findBetween(userId, start, end),
                reschedules.findBetween(userId, start, end)));
    }

    public List<ProjectProgressView> projects(UUID userId, ProjectStatus status, int limit, int offset) {
        LocalDate today = LocalDate.now(clock);
        return transactionManager.execute(Propagation.SUPPORTS,
                        () -> statistics.projectProgress(userId, status, today, pageSize(limit), Math.max(offset, 0)))
                .stream()
                .map(row -> new ProjectProgressView(row.projectId(), row.title(), row.status(), row.deadline(),
                        ProgressView.from(row.progress())))
                .toList();
    }

    public List<GoalProgressView> goals(UUID userId, int limit, int offset) {
        LocalDate today = LocalDate.now(clock);
        return transactionManager.execute(Propagation.SUPPORTS,
                        () -> statistics.goalProgress(userId, today, pageSize(limit), Math.max(offset, 0)))
                .stream()
                .map(row -> new GoalProgressView(row.goalId(), row.title(), row.projects(), ProgressView.from(row.progress())))
                .toList();
    }

    private static int pageSize(int limit) {
        return Math.clamp(limit, 1, MAX_PAGE_SIZE);
    }
}
