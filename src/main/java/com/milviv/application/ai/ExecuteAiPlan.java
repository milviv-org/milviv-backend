package com.milviv.application.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.application.ai.contract.PlanDraft.GoalNode;
import com.milviv.application.ai.contract.PlanDraft.ProjectNode;
import com.milviv.application.ai.contract.PlanDraft.RewardNode;
import com.milviv.application.ai.contract.PlanDraft.TaskNode;
import com.milviv.application.gamification.RewardCommands;
import com.milviv.application.gamification.RewardView;
import com.milviv.application.goal.GoalCommands;
import com.milviv.application.goal.GoalView;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.project.ProjectView;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiHandle;
import com.milviv.domain.ai.EntityChanges;
import com.milviv.domain.ai.OperationReport;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.ai.OperationReport.Item;
import com.milviv.domain.ai.OperationReport.Result;
import com.milviv.domain.ai.PendingOperation;
import com.milviv.domain.ai.PendingOperation.Op;
import com.milviv.domain.ai.ValidatedPlan;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.gamification.RewardDetails;
import com.milviv.domain.goal.GoalDetails;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.task.TaskDetails;

import static com.milviv.application.ai.contract.PlanDraft.list;
import static com.milviv.domain.ai.EntityChanges.apply;

/**
 * Исполнение плана AI через ручные сценарии (принцип 5): правила домена те же, что у ручного API,
 * userId — из сессии, а не от AI. Отчёт строится из того, что вернули сценарии (инвариант 6).
 *
 * Вызывается только внутри чужой транзакции — всё или ничего (§10.1):
 * создания — в транзакции 3 HandleAiRequest, изменения и удаления — в транзакции DecideAiProposal.
 */
public class ExecuteAiPlan {

    private final GoalCommands goals;
    private final ProjectCommands projects;
    private final TaskCommands tasks;
    private final RewardCommands rewards;
    private final AiTargets targets;
    private final TransactionManager transactionManager;

    public ExecuteAiPlan(GoalCommands goals, ProjectCommands projects, TaskCommands tasks, RewardCommands rewards,
                         TransactionManager transactionManager) {
        this.goals = goals;
        this.projects = projects;
        this.tasks = tasks;
        this.rewards = rewards;
        this.targets = new AiTargets(goals, projects, tasks, rewards);
        this.transactionManager = transactionManager;
    }

    /** @param refs ref нового проекта → его ID: на него могут ссылаться изменения */
    public record Creations(OperationReport report, Map<String, UUID> refs) {}

    // ─── Создания ─────────────────────────────────────────────────────────────

    /**
     * Порядок: цель → её проекты → их задачи → задачи цели → проекты без цели → их задачи →
     * задачи без проекта → награды. Родитель всегда создан раньше детей.
     *
     * Существующая цель или проект (existing) не создаются и не попадают в отчёт: дескриптор → ID
     * через реестр. Сущность могли удалить после чтения — тогда сценарий бросит ошибку домена.
     *
     * @param create null — создавать нечего, план только из изменений и удалений
     */
    public Creations executeCreations(UUID userId, PlanDraft.Create create, HandleRegistry registry) {
        // MANDATORY: без внешней транзакции частичное создание было бы возможно
        return transactionManager.execute(Propagation.MANDATORY, () -> {
            List<Item> report = new ArrayList<>();
            Map<String, UUID> refs = new HashMap<>();
            if (create == null) {
                return new Creations(new OperationReport(report), refs);
            }
            GoalNode goalNode = create.goal();
            if (goalNode != null) {
                UUID goalId;
                if (goalNode.existing() != null) {
                    goalId = id(registry, goalNode.existing(), EntityType.GOAL);
                } else {
                    GoalView goal = goals.create(userId, new GoalDetails(goalNode.title(), goalNode.description()));
                    report.add(new Item("g1", Result.CREATED, EntityType.GOAL, goal.id(), goal.title()));
                    goalId = goal.id();
                }
                createProjects(userId, "g1.p", list(goalNode.projects()), goalId, registry, report, refs);
                createTasks(userId, "g1.t", list(goalNode.tasks()), null, goalId, report);
            }
            createProjects(userId, "p", list(create.projects()), null, registry, report, refs);
            createTasks(userId, "t", list(create.tasks()), null, null, report);

            List<RewardNode> rewardNodes = list(create.rewards());
            for (int i = 0; i < rewardNodes.size(); i++) {
                RewardNode node = rewardNodes.get(i);
                // скрытые награды AI не создаёт (G-5), пароль не нужен
                RewardView reward = rewards.create(userId, new RewardDetails(node.title(), node.description(), node.cost(), false), null);
                report.add(new Item("r" + (i + 1), Result.CREATED, EntityType.REWARD, reward.id(), reward.title()));
            }
            return new Creations(new OperationReport(report), refs);
        });
    }

    private void createProjects(UUID userId, String prefix, List<ProjectNode> nodes, UUID goalId,
                                HandleRegistry registry, List<Item> report, Map<String, UUID> refs) {
        for (int i = 0; i < nodes.size(); i++) {
            ProjectNode node = nodes.get(i);
            String itemId = prefix + (i + 1);
            UUID projectId;
            if (node.existing() != null) {
                projectId = id(registry, node.existing(), EntityType.PROJECT);
            } else {
                ProjectView project = projects.create(userId,
                        new ProjectDetails(goalId, node.title(), node.description(), node.deadline()));
                report.add(new Item(itemId, Result.CREATED, EntityType.PROJECT, project.id(), project.title()));
                projectId = project.id();
                if (node.ref() != null) {
                    refs.put(node.ref(), projectId);
                }
            }
            createTasks(userId, itemId + ".t", list(node.tasks()), projectId, null, report);
        }
    }

    /** План уже прошёл AiPlanPolicy: неизвестный дескриптор здесь — ошибка программы. */
    private static UUID id(HandleRegistry registry, String handle, EntityType type) {
        return registry.resolve(handle, type).map(AiHandle::id)
                .orElseThrow(() -> new IllegalStateException("Unresolved handle " + handle));
    }

    private void createTasks(UUID userId, String prefix, List<TaskNode> nodes, UUID projectId, UUID goalId,
                             List<Item> report) {
        for (int i = 0; i < nodes.size(); i++) {
            TaskNode node = nodes.get(i);
            TaskView task = tasks.create(userId, new TaskDetails(node.title(), node.description(), node.difficulty(),
                    node.points(), node.startDate(), node.deadline(), null, projectId, goalId));
            report.add(new Item(prefix + (i + 1), Result.CREATED, EntityType.TASK, task.id(), task.title()));
        }
    }

    // ─── Изменения и удаления ─────────────────────────────────────────────────

    /**
     * Исполняет только подтверждённые операции (G-3); остальные — CANCELLED.
     *
     * 1. Версии всех подтверждённых целей сверяются до первого изменения: пользователь соглашался
     *    на сущность в том состоянии, в каком её видел (§10.1). Не совпала или исчезла → TARGET_CHANGED.
     * 2. Порядок (K-9) подобран так, что промежуточное состояние допустимо, если допустимо итоговое:
     *    цели и награды → проекты, чей срок не уменьшается → задачи → удаления задач →
     *    проекты, чей срок уменьшается → удаления проектов, целей, наград.
     *
     * @return по операции на каждый элемент карточки, в порядке карточки
     */
    public List<Item> executeChanges(UUID userId, ValidatedPlan plan, Set<String> approved) {
        return transactionManager.execute(Propagation.MANDATORY, () -> {
            Map<String, AiTargets.Current> current = new HashMap<>();
            List<PendingOperation> chosen = plan.operations().stream()
                    .filter(op -> approved.contains(op.itemId()))
                    .toList();
            for (PendingOperation op : chosen) {
                AiTargets.Current state = targets.find(userId, op.type(), op.targetId())
                        // исчезнувшую награду могли сделать скрытой — без названия (G-5)
                        .orElseThrow(() -> AiRequestException.targetChanged(op.type() == EntityType.REWARD ? null : op.title()));
                if (state.version() != op.expectedVersion()) {
                    throw AiRequestException.targetChanged(state.title());
                }
                current.put(op.itemId(), state);
            }

            Map<String, Item> done = new HashMap<>();
            chosen.stream()
                    .sorted(Comparator.comparingInt(op -> stage(op, current.get(op.itemId()))))
                    .forEach(op -> done.put(op.itemId(), execute(userId, op, current.get(op.itemId()))));

            return plan.operations().stream()
                    .map(op -> done.getOrDefault(op.itemId(),
                            new Item(op.itemId(), Result.CANCELLED, op.type(), op.targetId(), op.title())))
                    .toList();
        });
    }

    private static int stage(PendingOperation op, AiTargets.Current current) {
        if (op.op() == Op.DELETE) {
            return op.type() == EntityType.TASK ? 4 : 6;
        }
        return switch (op.type()) {
            case GOAL, REWARD -> 1;
            case TASK -> 3;
            case PROJECT -> shrinksDeadline(op.changes(), current) ? 5 : 2;
        };
    }

    private static boolean shrinksDeadline(EntityChanges changes, AiTargets.Current current) {
        if (changes.deadline() == null || changes.deadline().value() == null) {
            return false;
        }
        return current.deadline() == null || changes.deadline().value().isBefore(current.deadline());
    }

    private Item execute(UUID userId, PendingOperation op, AiTargets.Current current) {
        UUID id = op.targetId();
        if (op.op() == Op.DELETE) {
            switch (op.type()) {
                case GOAL -> goals.delete(userId, id);
                case PROJECT -> projects.delete(userId, id);
                case TASK -> tasks.delete(userId, id);
                case REWARD -> rewards.delete(userId, id, null);
            }
            return new Item(op.itemId(), Result.DELETED, op.type(), null, current.title());
        }
        // set частичный, ручной update — полный: поля вне set остаются текущими
        EntityChanges c = op.changes();
        long version = op.expectedVersion();
        return switch (op.type()) {
            case TASK -> {
                TaskView task = tasks.get(userId, id);
                TaskDetails details = new TaskDetails(apply(c.title(), task.title()), apply(c.description(), task.description()),
                        apply(c.difficulty(), task.difficulty()), apply(c.points(), (Integer) task.points()),
                        apply(c.startDate(), task.startDate()), apply(c.deadline(), task.deadline()), task.reminderAt(),
                        apply(c.projectId(), task.projectId()), apply(c.goalId(), task.goalId()));
                TaskView updated = guarded(op, () -> tasks.update(userId, id, details, version));
                yield new Item(op.itemId(), Result.UPDATED, EntityType.TASK, updated.id(), updated.title());
            }
            case PROJECT -> {
                ProjectView project = projects.get(userId, id);
                ProjectDetails details = new ProjectDetails(apply(c.goalId(), project.goalId()),
                        apply(c.title(), project.title()), apply(c.description(), project.description()),
                        apply(c.deadline(), project.deadline()));
                ProjectView updated = guarded(op, () -> projects.update(userId, id, details, version));
                yield new Item(op.itemId(), Result.UPDATED, EntityType.PROJECT, updated.id(), updated.title());
            }
            case GOAL -> {
                GoalView goal = goals.get(userId, id);
                GoalDetails details = new GoalDetails(apply(c.title(), goal.title()), apply(c.description(), goal.description()));
                GoalView updated = guarded(op, () -> goals.update(userId, id, details, version));
                yield new Item(op.itemId(), Result.UPDATED, EntityType.GOAL, updated.id(), updated.title());
            }
            case REWARD -> {
                RewardView reward = rewards.get(userId, id, null);
                RewardDetails details = new RewardDetails(apply(c.title(), reward.title()),
                        apply(c.description(), reward.description()), apply(c.cost(), (Integer) reward.cost()), false);
                RewardView updated = guarded(op, () -> rewards.update(userId, id, details, version, null));
                yield new Item(op.itemId(), Result.UPDATED, EntityType.REWARD, updated.id(), updated.title());
            }
        };
    }

    /** Версия ручного update не совпала — сущность изменили параллельно со сверкой. */
    private static <T> T guarded(PendingOperation op, Supplier<T> update) {
        try {
            return update.get();
        } catch (ConflictException ex) {
            throw AiRequestException.targetChanged(op.title());
        }
    }
}
