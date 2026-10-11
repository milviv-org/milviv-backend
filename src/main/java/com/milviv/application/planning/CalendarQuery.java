package com.milviv.application.planning;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.milviv.application.project.ProjectView;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.planning.Plan;
import com.milviv.domain.planning.PlanRepository;
import com.milviv.domain.project.Project;
import com.milviv.domain.project.ProjectRepository;
import com.milviv.domain.task.Task;
import com.milviv.domain.task.TaskRepository;

/**
 * Календарь (FR-06): планы, сроки и выполненные задачи по дням. Даты — по UTC.
 * Все чтения — в одной транзакции только для чтения, чтобы дни были согласованы между собой.
 */
public class CalendarQuery {

    private final PlanRepository plans;
    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final CalendarPolicy policy;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public CalendarQuery(PlanRepository plans, TaskRepository tasks, ProjectRepository projects,
                         CalendarPolicy policy, TransactionManager transactionManager, Clock clock) {
        this.plans = plans;
        this.tasks = tasks;
        this.projects = projects;
        this.policy = policy;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    private record Data(List<Plan> plans, List<Task> deadlines, List<Task> completed, List<Project> projects) {}

    public CalendarView get(UUID userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ValidationException("to", "Конец периода раньше начала");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > policy.maxDays()) {
            throw new ValidationException("to", "Период не длиннее " + policy.maxDays() + " дней");
        }

        Data data = transactionManager.execute(Propagation.SUPPORTS, () -> new Data(
                plans.findForPeriod(userId, from, to),
                tasks.findActiveWithDeadlineBetween(userId, from, to),
                tasks.findCompletedBetween(userId, from.atStartOfDay().toInstant(ZoneOffset.UTC),
                        to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)),
                projects.findActiveWithDeadlineBetween(userId, from, to)));

        LocalDate today = LocalDate.now(clock);
        List<CalendarView.Day> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            LocalDate day = date;
            result.add(new CalendarView.Day(
                    day,
                    data.plans().stream()
                            .filter(plan -> plan.occursOn(day))
                            .sorted(Comparator.comparing(Plan::getTimeFrom))
                            .map(PlanView::from)
                            .toList(),
                    data.deadlines().stream()
                            .filter(task -> task.getDeadline().equals(day))
                            .map(task -> TaskView.from(task, today))
                            .toList(),
                    data.completed().stream()
                            .filter(task -> LocalDate.ofInstant(task.getCompletedAt(), ZoneOffset.UTC).equals(day))
                            .map(task -> TaskView.from(task, today))
                            .toList(),
                    data.projects().stream()
                            .filter(project -> project.getDeadline().equals(day))
                            .map(ProjectView::from)
                            .toList()));
        }
        return new CalendarView(from, to, result);
    }
}
