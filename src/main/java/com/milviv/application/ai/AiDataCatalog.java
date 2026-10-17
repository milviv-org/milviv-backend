package com.milviv.application.ai;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.DataQuery;
import com.milviv.application.ai.contract.DataQuery.Period;
import com.milviv.application.ai.contract.DataRound;
import com.milviv.application.ai.contract.DataRound.DataItem;
import com.milviv.application.ai.contract.DataRound.DataResult;
import com.milviv.application.ai.contract.DataRound.RejectedRequest;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiHandle;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.gamification.RewardRepository;
import com.milviv.domain.goal.GoalRepository;
import com.milviv.domain.planning.Plan;
import com.milviv.domain.planning.PlanRepository;
import com.milviv.domain.project.Project;
import com.milviv.domain.project.ProjectRepository;
import com.milviv.domain.reflection.ReflectionAnswer;
import com.milviv.domain.reflection.ReflectionAnswerRepository;
import com.milviv.domain.task.Task;
import com.milviv.domain.task.TaskFilter;
import com.milviv.domain.task.TaskRepository;

/**
 * Закрытый каталог данных для AI (§6.3) — единственное место, где решается, что видит AI (QG-01, G-5).
 *
 * - только запросы каталога, только user_id из сессии, через те же репозитории, что ручной API (принцип 5);
 * - скрытые награды отсекаются в SQL: не влияют ни на элементы, ни на truncated (T-6);
 * - каждой возвращённой цели, проекту, задаче, награде — дескриптор с версией (§6.4);
 * - ≤ maxItems элементов на запрос, тексты урезаются, общий бюджет токенов на AI-запрос.
 *
 * Чтение — в транзакции только для чтения; вызывается вне транзакции записи (AD-08).
 */
public class AiDataCatalog {

    /** Грубая оценка: символов на токен. Не зависит от токенизатора провайдера. */
    static final int CHARS_PER_TOKEN = 4;
    static final int PLANS_MAX_DAYS = 31;

    private final GoalRepository goals;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final RewardRepository rewards;
    private final PlanRepository plans;
    private final ReflectionAnswerRepository answers;
    private final AiLimits.Data limits;
    private final TransactionManager transactionManager;

    public AiDataCatalog(GoalRepository goals, ProjectRepository projects, TaskRepository tasks, RewardRepository rewards,
                         PlanRepository plans, ReflectionAnswerRepository answers, AiLimits limits,
                         TransactionManager transactionManager) {
        this.goals = goals;
        this.projects = projects;
        this.tasks = tasks;
        this.rewards = rewards;
        this.plans = plans;
        this.answers = answers;
        this.limits = limits.data();
        this.transactionManager = transactionManager;
    }

    /** Сколько токенов данных ещё можно отдать AI в этом запросе. */
    public static final class Budget {
        private int tokensLeft;

        public Budget(int tokens) {
            this.tokensLeft = tokens;
        }

        boolean take(DataItem item) {
            int tokens = Math.max(1, item.toString().length() / CHARS_PER_TOKEN);
            if (tokens > tokensLeft) {
                return false;
            }
            tokensLeft -= tokens;
            return true;
        }
    }

    /** Отклонение одного запроса раунда — остальные исполняются (§6.3). */
    private static final class Rejected extends RuntimeException {
        final String code;

        Rejected(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    public DataRound execute(UUID userId, int round, AiTurn.DataRequest request, HandleRegistry registry, Budget budget) {
        return transactionManager.execute(Propagation.SUPPORTS, () -> {
            List<DataResult> results = new ArrayList<>();
            List<RejectedRequest> rejected = new ArrayList<>();
            List<DataQuery> queries = request.dataRequests();
            for (int i = 0; i < queries.size(); i++) {
                DataQuery query = queries.get(i);
                try {
                    results.add(run(userId, query, registry, budget));
                } catch (Rejected ex) {
                    rejected.add(new RejectedRequest(i, query.query(), ex.code));
                }
            }
            return new DataRound(round, request.dataRequestReason(), results, rejected);
        });
    }

    private DataResult run(UUID userId, DataQuery query, HandleRegistry registry, Budget budget) {
        int fetch = limits.maxItems() + 1;
        return switch (query) {
            case DataQuery.ListGoals q -> result(q, goals.findByUser(userId, fetch, 0), goal -> new DataRound.GoalItem(
                    registry.issue(EntityType.GOAL, goal.getId(), goal.getVersion(), null), goal.getTitle(),
                    cut(goal.getDescription())), budget);

            case DataQuery.ListProjects q -> {
                UUID goalId = q.goal() == null ? null : resolve(registry, q.goal(), EntityType.GOAL);
                yield result(q, projects.findByUser(userId, q.status(), goalId, fetch, 0),
                        project -> projectItem(userId, project, registry), budget);
            }

            case DataQuery.ListTasks q -> {
                if (Boolean.TRUE.equals(q.unassigned()) && (q.project() != null || q.goal() != null)) {
                    throw new Rejected("CONFLICTING_PARAMS");
                }
                UUID projectId = q.project() == null ? null : resolve(registry, q.project(), EntityType.PROJECT);
                UUID goalId = q.goal() == null ? null : resolve(registry, q.goal(), EntityType.GOAL);
                Period period = period(q.period(), false, Integer.MAX_VALUE);
                TaskFilter filter = new TaskFilter(q.status(), projectId, goalId, Boolean.TRUE.equals(q.unassigned()),
                        period == null ? null : period.from(), period == null ? null : period.to());
                yield result(q, tasks.findByUser(userId, filter, fetch, 0), task -> taskItem(userId, task, registry), budget);
            }

            // скрытые отсекаются в SQL — до LIMIT, поэтому не влияют на truncated (G-5, T-6)
            case DataQuery.ListRewards q -> result(q, rewards.findByUser(userId, false, fetch, 0),
                    reward -> new DataRound.RewardItem(registry.issue(EntityType.REWARD, reward.getId(), reward.getVersion(), null),
                            reward.getTitle(), cut(reward.getDescription()), reward.getCost()), budget);

            case DataQuery.ListPlans q -> {
                Period period = period(q.period(), true, PLANS_MAX_DAYS);
                List<Plan> found = plans.findForPeriod(userId, period.from(), period.to());
                yield result(q, found.size() > fetch ? found.subList(0, fetch) : found,
                        plan -> new DataRound.PlanItem(plan.getPlanDate(), plan.getDaysOfWeek(), plan.getTimeFrom(),
                                plan.getTimeTo(), plan.getTitle(), taskHandle(userId, plan.getTaskId(), registry)), budget);
            }

            case DataQuery.ListReflections q -> {
                UUID taskId = q.task() == null ? null : resolve(registry, q.task(), EntityType.TASK);
                Period period = period(q.period(), false, Integer.MAX_VALUE);
                Instant from = period == null ? null : period.from().atStartOfDay().toInstant(ZoneOffset.UTC);
                Instant to = period == null ? null : period.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                List<ReflectionAnswer> found = answers.findForAi(userId, taskId, from, to, fetch);
                yield result(q, found, answer -> new DataRound.ReflectionItem(
                        taskHandle(userId, answer.taskId(), registry), answer.createdAt(),
                        answer.questionText(), cut(answer.answer())), budget);
            }
        };
    }

    // ─── Проекции ─────────────────────────────────────────────────────────────

    private DataItem projectItem(UUID userId, Project project, HandleRegistry registry) {
        return new DataRound.ProjectItem(
                registry.issue(EntityType.PROJECT, project.getId(), project.getVersion(), project.getDeadline()),
                project.getTitle(), cut(project.getDescription()), project.getStatus(), project.getDeadline(),
                goalHandle(userId, project.getGoalId(), registry));
    }

    private DataItem taskItem(UUID userId, Task task, HandleRegistry registry) {
        return new DataRound.TaskItem(
                registry.issue(EntityType.TASK, task.getId(), task.getVersion(), task.getDeadline(), task.getProjectId()),
                task.getTitle(), cut(task.getDescription()), task.getStatus(), task.getDifficulty(), task.getPoints(),
                task.getStartDate(), task.getDeadline(), task.getCompletedAt(),
                projectHandle(userId, task.getProjectId(), registry), goalHandle(userId, task.getGoalId(), registry));
    }

    /** Связанная сущность дочитывается, чтобы у её дескриптора была версия, — на неё можно сослаться дальше. */
    private String goalHandle(UUID userId, UUID goalId, HandleRegistry registry) {
        return linked(goalId, registry, () -> goals.findById(userId, goalId)
                .map(g -> registry.issue(EntityType.GOAL, g.getId(), g.getVersion(), null)));
    }

    private String projectHandle(UUID userId, UUID projectId, HandleRegistry registry) {
        return linked(projectId, registry, () -> projects.findById(userId, projectId)
                .map(p -> registry.issue(EntityType.PROJECT, p.getId(), p.getVersion(), p.getDeadline())));
    }

    private String taskHandle(UUID userId, UUID taskId, HandleRegistry registry) {
        return linked(taskId, registry, () -> tasks.findById(userId, taskId)
                .map(t -> registry.issue(EntityType.TASK, t.getId(), t.getVersion(), t.getDeadline(), t.getProjectId())));
    }

    private static String linked(UUID id, HandleRegistry registry, Supplier<Optional<String>> load) {
        if (id == null) {
            return null;
        }
        return registry.handleOf(id).or(load).orElse(null);
    }

    private <T> DataResult result(DataQuery query, List<T> found, Function<T, DataItem> project, Budget budget) {
        boolean truncated = found.size() > limits.maxItems();
        List<DataItem> items = new ArrayList<>();
        for (T entity : truncated ? found.subList(0, limits.maxItems()) : found) {
            DataItem item = project.apply(entity);
            if (!budget.take(item)) {
                truncated = true;
                break;
            }
            items.add(item);
        }
        return new DataResult(query.query(), truncated, items);
    }

    // ─── Параметры ────────────────────────────────────────────────────────────

    private static UUID resolve(HandleRegistry registry, String handle, EntityType type) {
        return registry.resolve(handle, type).map(AiHandle::id).orElseThrow(() -> new Rejected("UNKNOWN_HANDLE"));
    }

    private static Period period(Period period, boolean required, int maxDays) {
        if (period == null) {
            if (required) {
                throw new Rejected("PERIOD_REQUIRED");
            }
            return null;
        }
        LocalDate from = period.from();
        LocalDate to = period.to();
        if (from == null || to == null || from.isAfter(to)) {
            throw new Rejected("INVALID_PERIOD");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw new Rejected("PERIOD_TOO_LONG");
        }
        return period;
    }

    private String cut(String text) {
        if (text == null || text.length() <= limits.textMaxLength()) {
            return text;
        }
        return text.substring(0, limits.textMaxLength());
    }
}
