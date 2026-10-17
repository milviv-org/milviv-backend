package com.milviv.application.ai;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.application.ai.contract.PlanDraft.GoalNode;
import com.milviv.application.ai.contract.PlanDraft.ProjectNode;
import com.milviv.application.ai.contract.PlanDraft.RewardNode;
import com.milviv.application.ai.contract.PlanDraft.TaskNode;
import com.milviv.application.ai.contract.PlanDraft.TaskSet;
import com.milviv.domain.ai.AiHandle;
import com.milviv.domain.ai.FieldChange;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.task.Task;

import static com.milviv.application.ai.contract.PlanDraft.list;

/**
 * Проверка плана AI до исполнения (§8). Лимиты AI строже ручных, но не мягче;
 * правила домена проверяются здесь заранее — чтобы AI мог исправиться — и ещё раз в сценариях.
 *
 * Превышение количественных лимитов — tooLarge: такой план не исправляется повтором,
 * иначе AI молча выбросил бы часть операций (TO-8).
 *
 * existing и target — только дескрипторы, выданные каталогом в этом запросе, подходящего типа (§6.4).
 * Даты итогового состояния проверяются по тому, что AI прочитал; остальное проверит домен при исполнении.
 */
public class AiPlanPolicy {

    private static final Pattern REF = Pattern.compile("^n_[a-z0-9]{1,8}$");

    private final AiLimits.Plan limits;

    public AiPlanPolicy(AiLimits limits) {
        this.limits = limits.plan();
    }

    /** @param tooLarge превышены количественные лимиты */
    public record Verdict(List<String> violations, boolean tooLarge) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    public Verdict check(PlanDraft plan, LocalDate today, HandleRegistry registry) {
        if (plan == null) {
            return new Verdict(List.of("plan: REQUIRED"), false);
        }
        PlanDraft.Create create = plan.create();
        boolean noCreates = create == null || isEmpty(create);
        if (noCreates && !plan.hasChanges()) {
            return new Verdict(List.of("plan: EMPTY"), false);
        }

        List<String> tooLarge = new ArrayList<>(noCreates ? List.of() : counts(create));
        PlanDraft.Update update = plan.update() == null ? new PlanDraft.Update(null, null, null, null) : plan.update();
        int updates = list(update.tasks()).size() + list(update.projects()).size() + list(update.goals()).size()
                + list(update.rewards()).size();
        if (updates > limits.maxUpdates()) {
            tooLarge.add("plan.update: UPDATES_MAX(" + limits.maxUpdates() + ")");
        }
        if (plan.deletes().size() > limits.maxDeletes()) {
            tooLarge.add("plan.delete: DELETES_MAX(" + limits.maxDeletes() + ")");
        }
        if (!tooLarge.isEmpty()) {
            return new Verdict(tooLarge, true);
        }

        Check check = new Check(registry, today.plusYears(limits.dateHorizonYears()));
        check.deletes(plan.deletes());
        check.duplicates(update, plan.deletes());
        if (!noCreates) {
            check.creations(create);
        }
        check.updates(update);
        return new Verdict(check.violations, false);
    }

    // ─── Количества созданий ──────────────────────────────────────────────────

    /** Сколько сущностей план создаст: существующие цель и проекты не в счёт. */
    public static int countCreates(PlanDraft plan) {
        PlanDraft.Create create = plan == null ? null : plan.create();
        if (create == null) {
            return 0;
        }
        GoalNode goal = create.goal();
        List<ProjectNode> projects = new ArrayList<>(list(create.projects()));
        int count = list(create.tasks()).size() + list(create.rewards()).size();
        if (goal != null) {
            count += goal.existing() == null ? 1 : 0;
            count += list(goal.tasks()).size();
            projects.addAll(list(goal.projects()));
        }
        for (ProjectNode project : projects) {
            count += (project.existing() == null ? 1 : 0) + list(project.tasks()).size();
        }
        return count;
    }

    private static boolean isEmpty(PlanDraft.Create create) {
        return create.goal() == null && list(create.projects()).isEmpty() && list(create.tasks()).isEmpty()
                && list(create.rewards()).isEmpty();
    }

    /** Существующие цель и проекты не создаются — в лимиты не входят. */
    private List<String> counts(PlanDraft.Create create) {
        List<String> found = new ArrayList<>();
        GoalNode goal = create.goal();
        List<ProjectNode> allProjects = new ArrayList<>(list(create.projects()));
        List<List<TaskNode>> taskLists = new ArrayList<>(List.of(list(create.tasks())));
        if (goal != null) {
            allProjects.addAll(list(goal.projects()));
            taskLists.add(list(goal.tasks()));
        }
        allProjects.forEach(project -> taskLists.add(list(project.tasks())));

        int newGoals = goal != null && goal.existing() == null ? 1 : 0;
        int newProjects = (int) allProjects.stream().filter(p -> p.existing() == null).count();
        int tasks = taskLists.stream().mapToInt(List::size).sum();
        int rewards = list(create.rewards()).size();
        int total = newGoals + newProjects + tasks + rewards;

        if (newProjects > limits.maxProjects()) {
            found.add("plan.create: PROJECTS_MAX(" + limits.maxProjects() + ")");
        }
        if (taskLists.stream().anyMatch(node -> node.size() > limits.maxTasksPerNode())) {
            found.add("plan.create: TASKS_PER_NODE_MAX(" + limits.maxTasksPerNode() + ")");
        }
        if (tasks > limits.maxTasksTotal()) {
            found.add("plan.create: TASKS_MAX(" + limits.maxTasksTotal() + ")");
        }
        if (rewards > limits.maxRewards()) {
            found.add("plan.create: REWARDS_MAX(" + limits.maxRewards() + ")");
        }
        if (total > limits.maxCreates()) {
            found.add("plan.create: CREATES_MAX(" + limits.maxCreates() + ")");
        }
        return found;
    }

    /** Одна проверка плана: замечания и то, что о плане уже известно. */
    private final class Check {

        final List<String> violations = new ArrayList<>();
        final HandleRegistry registry;
        final LocalDate horizon;
        /** ID удаляемых сущностей — на них нельзя ссылаться. */
        final Set<UUID> deleted = new HashSet<>();
        /** ref нового проекта → его срок. */
        final Map<String, LocalDate> refs = new HashMap<>();
        /** Новые задачи в существующих проектах: ID проекта → их сроки. */
        final Map<UUID, List<LocalDate>> newTaskDeadlines = new HashMap<>();

        Check(HandleRegistry registry, LocalDate horizon) {
            this.registry = registry;
            this.horizon = horizon;
        }

        // ─── Удаления и конфликты ─────────────────────────────────────────────

        void deletes(List<PlanDraft.Delete> deletes) {
            for (int i = 0; i < deletes.size(); i++) {
                PlanDraft.Delete delete = deletes.get(i);
                Optional<AiHandle> target = delete == null ? Optional.empty() : registry.find(delete.target());
                if (target.isEmpty()) {
                    violations.add("plan.delete[" + i + "].target: UNKNOWN_HANDLE");
                } else {
                    deleted.add(target.get().id());
                }
            }
        }

        /** Одна сущность — не больше одной операции: изменить дважды или изменить и удалить нельзя. */
        void duplicates(PlanDraft.Update update, List<PlanDraft.Delete> deletes) {
            Set<String> seen = new HashSet<>();
            List<PlanDraft.TaskUpdate> tasks = list(update.tasks());
            for (int i = 0; i < tasks.size(); i++) {
                duplicate(seen, "plan.update.tasks[" + i + "]", tasks.get(i) == null ? null : tasks.get(i).target());
            }
            List<PlanDraft.ProjectUpdate> projects = list(update.projects());
            for (int i = 0; i < projects.size(); i++) {
                duplicate(seen, "plan.update.projects[" + i + "]", projects.get(i) == null ? null : projects.get(i).target());
            }
            List<PlanDraft.GoalUpdate> goals = list(update.goals());
            for (int i = 0; i < goals.size(); i++) {
                duplicate(seen, "plan.update.goals[" + i + "]", goals.get(i) == null ? null : goals.get(i).target());
            }
            List<PlanDraft.RewardUpdate> rewards = list(update.rewards());
            for (int i = 0; i < rewards.size(); i++) {
                duplicate(seen, "plan.update.rewards[" + i + "]", rewards.get(i) == null ? null : rewards.get(i).target());
            }
            for (int i = 0; i < deletes.size(); i++) {
                duplicate(seen, "plan.delete[" + i + "]", deletes.get(i) == null ? null : deletes.get(i).target());
            }
        }

        private void duplicate(Set<String> seen, String at, String target) {
            if (target != null && !seen.add(target)) {
                violations.add(at + ".target: DUPLICATE_TARGET");
            }
        }

        // ─── Создания ─────────────────────────────────────────────────────────

        void creations(PlanDraft.Create create) {
            if (create.goal() != null) {
                GoalNode goal = create.goal();
                String at = "plan.create.goal";
                if (goal.existing() != null) {
                    existing(at, goal.existing(), EntityType.GOAL);
                    if (goal.title() != null || goal.description() != null) {
                        violations.add(at + ": EXISTING_WITH_FIELDS");
                    }
                } else {
                    texts(at, goal.title(), goal.description());
                }
                List<ProjectNode> goalProjects = list(goal.projects());
                for (int i = 0; i < goalProjects.size(); i++) {
                    // существующий проект в цель — это изменение проекта, оно требует согласия
                    if (goalProjects.get(i).existing() != null) {
                        violations.add(at + ".projects[" + i + "].existing: NOT_ALLOWED");
                    }
                }
                projects(at + ".projects", goalProjects);
                tasks(at + ".tasks", list(goal.tasks()), null);
            }
            projects("plan.create.projects", list(create.projects()));
            tasks("plan.create.tasks", list(create.tasks()), null);
            rewards(list(create.rewards()));
        }

        private void projects(String path, List<ProjectNode> projects) {
            for (int i = 0; i < projects.size(); i++) {
                ProjectNode project = projects.get(i);
                String at = path + "[" + i + "]";
                LocalDate deadline;
                if (project.existing() != null) {
                    Optional<AiHandle> handle = existing(at, project.existing(), EntityType.PROJECT);
                    if (project.ref() != null || project.title() != null || project.description() != null
                            || project.deadline() != null) {
                        violations.add(at + ": EXISTING_WITH_FIELDS");
                    }
                    // срок существующего проекта — запомненный при чтении; домен перепроверит при исполнении
                    deadline = handle.map(AiHandle::deadline).orElse(null);
                    handle.ifPresent(h -> list(project.tasks()).forEach(task -> {
                        if (task.deadline() != null) {
                            newTaskDeadlines.computeIfAbsent(h.id(), id -> new ArrayList<>()).add(task.deadline());
                        }
                    }));
                } else {
                    texts(at, project.title(), project.description());
                    date(at + ".deadline", project.deadline());
                    deadline = project.deadline();
                    ref(at, project.ref(), deadline);
                }
                tasks(at + ".tasks", list(project.tasks()), deadline);
            }
        }

        private void ref(String at, String ref, LocalDate deadline) {
            if (ref == null) {
                return;
            }
            if (!REF.matcher(ref).matches()) {
                violations.add(at + ".ref: INVALID_REF");
            } else if (refs.containsKey(ref)) {
                violations.add(at + ".ref: DUPLICATE_REF");
            } else {
                refs.put(ref, deadline);
            }
        }

        private Optional<AiHandle> existing(String at, String handle, EntityType type) {
            Optional<AiHandle> resolved = registry.resolve(handle, type);
            if (resolved.isEmpty()) {
                violations.add(at + ".existing: UNKNOWN_HANDLE");
            } else if (deleted.contains(resolved.get().id())) {
                violations.add(at + ".existing: REFERENCES_DELETED");
            }
            return resolved;
        }

        /** @param projectDeadline срок проекта этих задач; null — проекта нет или срока нет */
        private void tasks(String path, List<TaskNode> tasks, LocalDate projectDeadline) {
            for (int i = 0; i < tasks.size(); i++) {
                TaskNode task = tasks.get(i);
                String at = path + "[" + i + "]";
                texts(at, task.title(), task.description());
                if (task.difficulty() == null) {
                    violations.add(at + ".difficulty: REQUIRED");
                }
                points(at + ".points", task.points());
                date(at + ".startDate", task.startDate());
                date(at + ".deadline", task.deadline());
                if (task.startDate() != null && task.deadline() != null && task.startDate().isAfter(task.deadline())) {
                    violations.add(at + ".startDate: AFTER_DEADLINE");
                }
                afterProjectDeadline(at + ".deadline", task.deadline(), projectDeadline);
            }
        }

        private void rewards(List<RewardNode> rewards) {
            for (int i = 0; i < rewards.size(); i++) {
                RewardNode reward = rewards.get(i);
                String at = "plan.create.rewards[" + i + "]";
                texts(at, reward.title(), reward.description());
                if (reward.cost() == null) {
                    violations.add(at + ".cost: REQUIRED");
                } else {
                    cost(at + ".cost", reward.cost());
                }
            }
        }

        // ─── Изменения ────────────────────────────────────────────────────────

        void updates(PlanDraft.Update update) {
            Map<UUID, TaskSet> taskSets = new HashMap<>();
            Map<UUID, FieldChange<LocalDate>> projectDeadlines = new HashMap<>();

            List<PlanDraft.ProjectUpdate> projects = list(update.projects());
            List<Optional<AiHandle>> projectTargets = new ArrayList<>();
            for (int i = 0; i < projects.size(); i++) {
                String at = "plan.update.projects[" + i + "]";
                PlanDraft.ProjectUpdate project = projects.get(i);
                Optional<AiHandle> target = target(at, project == null ? null : project.target(), EntityType.PROJECT);
                projectTargets.add(target);
                PlanDraft.ProjectSet set = project == null ? null : project.set();
                if (set == null || (set.title() == null && set.description() == null && set.deadline() == null
                        && set.goal() == null)) {
                    violations.add(at + ".set: EMPTY_SET");
                    continue;
                }
                title(at + ".set.title", set.title());
                description(at + ".set.description", set.description());
                if (set.deadline() != null) {
                    date(at + ".set.deadline", set.deadline().value());
                    target.ifPresent(h -> projectDeadlines.put(h.id(), set.deadline()));
                }
                link(at + ".set.goal", set.goal(), EntityType.GOAL);
            }

            List<PlanDraft.TaskUpdate> tasks = list(update.tasks());
            List<Optional<AiHandle>> taskTargets = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                String at = "plan.update.tasks[" + i + "]";
                PlanDraft.TaskUpdate task = tasks.get(i);
                Optional<AiHandle> target = target(at, task == null ? null : task.target(), EntityType.TASK);
                taskTargets.add(target);
                TaskSet set = task == null ? null : task.set();
                if (set == null || (set.title() == null && set.description() == null && set.difficulty() == null
                        && set.points() == null && set.startDate() == null && set.deadline() == null
                        && set.project() == null && set.goal() == null)) {
                    violations.add(at + ".set: EMPTY_SET");
                    continue;
                }
                title(at + ".set.title", set.title());
                description(at + ".set.description", set.description());
                if (set.difficulty() != null && set.difficulty().value() == null) {
                    violations.add(at + ".set.difficulty: REQUIRED");
                }
                if (set.points() != null) {
                    points(at + ".set.points", set.points().value());
                }
                LocalDate start = set.startDate() == null ? null : set.startDate().value();
                LocalDate deadline = set.deadline() == null ? null : set.deadline().value();
                date(at + ".set.startDate", start);
                date(at + ".set.deadline", deadline);
                if (start != null && deadline != null && start.isAfter(deadline)) {
                    violations.add(at + ".set.startDate: AFTER_DEADLINE");
                }
                if (set.project() != null && set.project().value() != null && set.project().value().startsWith("n_")) {
                    if (!refs.containsKey(set.project().value())) {
                        violations.add(at + ".set.project: UNKNOWN_REF");
                    }
                } else {
                    link(at + ".set.project", set.project(), EntityType.PROJECT);
                }
                link(at + ".set.goal", set.goal(), EntityType.GOAL);
                target.ifPresent(h -> taskSets.put(h.id(), set));
            }

            List<PlanDraft.GoalUpdate> goals = list(update.goals());
            for (int i = 0; i < goals.size(); i++) {
                String at = "plan.update.goals[" + i + "]";
                PlanDraft.GoalUpdate goal = goals.get(i);
                target(at, goal == null ? null : goal.target(), EntityType.GOAL);
                PlanDraft.GoalSet set = goal == null ? null : goal.set();
                if (set == null || (set.title() == null && set.description() == null)) {
                    violations.add(at + ".set: EMPTY_SET");
                    continue;
                }
                title(at + ".set.title", set.title());
                description(at + ".set.description", set.description());
            }

            List<PlanDraft.RewardUpdate> rewards = list(update.rewards());
            for (int i = 0; i < rewards.size(); i++) {
                String at = "plan.update.rewards[" + i + "]";
                PlanDraft.RewardUpdate reward = rewards.get(i);
                // скрытой награде дескриптор не выдаётся — сослаться на неё нельзя (G-5)
                target(at, reward == null ? null : reward.target(), EntityType.REWARD);
                PlanDraft.RewardSet set = reward == null ? null : reward.set();
                if (set == null || (set.title() == null && set.description() == null && set.cost() == null)) {
                    violations.add(at + ".set: EMPTY_SET");
                    continue;
                }
                title(at + ".set.title", set.title());
                description(at + ".set.description", set.description());
                if (set.cost() != null) {
                    if (set.cost().value() == null) {
                        violations.add(at + ".set.cost: REQUIRED");
                    } else {
                        cost(at + ".set.cost", set.cost().value());
                    }
                }
            }

            FinalState state = new FinalState(taskSets, projectDeadlines);
            for (int i = 0; i < tasks.size(); i++) {
                TaskSet set = tasks.get(i) == null ? null : tasks.get(i).set();
                String at = "plan.update.tasks[" + i + "].set.deadline";
                if (set != null && (set.deadline() != null || set.project() != null)) {
                    taskTargets.get(i).ifPresent(task -> afterProjectDeadline(at,
                            state.taskDeadline(task), state.projectDeadlineOfTask(task)));
                }
            }
            for (int i = 0; i < projects.size(); i++) {
                PlanDraft.ProjectSet set = projects.get(i) == null ? null : projects.get(i).set();
                if (set != null && set.deadline() != null && set.deadline().value() != null) {
                    LocalDate deadline = set.deadline().value();
                    String at = "plan.update.projects[" + i + "].set.deadline";
                    projectTargets.get(i).flatMap(project -> state.latestTaskDeadline(project.id()))
                            .filter(latest -> latest.isAfter(deadline))
                            .ifPresent(latest -> violations.add(at + ": BEFORE_TASK_DEADLINE(" + latest + ")"));
                }
            }
        }

        private Optional<AiHandle> target(String at, String handle, EntityType type) {
            Optional<AiHandle> resolved = registry.resolve(handle, type);
            if (resolved.isEmpty()) {
                violations.add(at + ".target: UNKNOWN_HANDLE");
            }
            return resolved;
        }

        /** Ссылка из set на другую сущность: FieldChange(null) — убрать связь. */
        private void link(String at, FieldChange<String> change, EntityType type) {
            if (change == null || change.value() == null) {
                return;
            }
            Optional<AiHandle> resolved = registry.resolve(change.value(), type);
            if (resolved.isEmpty()) {
                violations.add(at + ": UNKNOWN_HANDLE");
            } else if (deleted.contains(resolved.get().id())) {
                violations.add(at + ": REFERENCES_DELETED");
            }
        }

        /**
         * Итоговое состояние, если пользователь подтвердит всё: сроки и проекты задач,
         * которые AI прочитал, с учётом изменений плана. Удаляемые задачи не учитываются.
         */
        private final class FinalState {
            final Map<UUID, TaskSet> taskSets;
            final Map<UUID, FieldChange<LocalDate>> projectDeadlines;

            FinalState(Map<UUID, TaskSet> taskSets, Map<UUID, FieldChange<LocalDate>> projectDeadlines) {
                this.taskSets = taskSets;
                this.projectDeadlines = projectDeadlines;
            }

            LocalDate taskDeadline(AiHandle task) {
                TaskSet set = taskSets.get(task.id());
                return set != null && set.deadline() != null ? set.deadline().value() : task.deadline();
            }

            LocalDate projectDeadlineOfTask(AiHandle task) {
                TaskSet set = taskSets.get(task.id());
                if (set != null && set.project() != null) {
                    String project = set.project().value();
                    if (project == null) {
                        return null;
                    }
                    if (project.startsWith("n_")) {
                        return refs.get(project);
                    }
                    return registry.resolve(project, EntityType.PROJECT).map(h -> projectDeadline(h.id())).orElse(null);
                }
                return task.projectId() == null ? null : projectDeadline(task.projectId());
            }

            /** null — проекта с таким ID AI не видел или у него нет срока. */
            UUID projectOfTask(AiHandle task) {
                TaskSet set = taskSets.get(task.id());
                if (set != null && set.project() != null) {
                    String project = set.project().value();
                    if (project == null || project.startsWith("n_")) {
                        return null;
                    }
                    return registry.resolve(project, EntityType.PROJECT).map(AiHandle::id).orElse(null);
                }
                return task.projectId();
            }

            LocalDate projectDeadline(UUID projectId) {
                FieldChange<LocalDate> change = projectDeadlines.get(projectId);
                if (change != null) {
                    return change.value();
                }
                return registry.handleOf(projectId).flatMap(registry::find).map(AiHandle::deadline).orElse(null);
            }

            /** Самый поздний срок задач проекта: прочитанных AI и новых из этого же плана. */
            Optional<LocalDate> latestTaskDeadline(UUID projectId) {
                List<LocalDate> deadlines = new ArrayList<>(newTaskDeadlines.getOrDefault(projectId, List.of()));
                registry.snapshot().stream()
                        .filter(h -> h.type() == EntityType.TASK && !deleted.contains(h.id()))
                        .filter(h -> projectId.equals(projectOfTask(h)))
                        .map(this::taskDeadline)
                        .filter(Objects::nonNull)
                        .forEach(deadlines::add);
                return deadlines.stream().max(LocalDate::compareTo);
            }
        }

        // ─── Поля ─────────────────────────────────────────────────────────────

        private void title(String at, FieldChange<String> change) {
            if (change == null) {
                return;
            }
            if (change.value() == null || change.value().isBlank()) {
                violations.add(at + ": REQUIRED");
            } else if (change.value().length() > limits.titleMaxLength()) {
                violations.add(at + ": MAX_LENGTH(" + limits.titleMaxLength() + ")");
            }
        }

        private void description(String at, FieldChange<String> change) {
            if (change != null && change.value() != null && change.value().length() > limits.descriptionMaxLength()) {
                violations.add(at + ": MAX_LENGTH(" + limits.descriptionMaxLength() + ")");
            }
        }

        private void texts(String at, String title, String description) {
            if (title == null || title.isBlank()) {
                violations.add(at + ".title: REQUIRED");
            } else if (title.length() > limits.titleMaxLength()) {
                violations.add(at + ".title: MAX_LENGTH(" + limits.titleMaxLength() + ")");
            }
            if (description != null && description.length() > limits.descriptionMaxLength()) {
                violations.add(at + ".description: MAX_LENGTH(" + limits.descriptionMaxLength() + ")");
            }
        }

        private void points(String at, Integer points) {
            if (points != null && (points < Task.MIN_POINTS || points > Task.MAX_POINTS)) {
                violations.add(at + ": RANGE(" + Task.MIN_POINTS + ".." + Task.MAX_POINTS + ")");
            }
        }

        private void cost(String at, int cost) {
            if (cost < 1 || cost > limits.rewardCostMax()) {
                violations.add(at + ": RANGE(1.." + limits.rewardCostMax() + ")");
            }
        }

        /** В прошлом можно (G-23); дальше горизонта — нет. */
        private void date(String at, LocalDate date) {
            if (date != null && date.isAfter(horizon)) {
                violations.add(at + ": AFTER_HORIZON(" + horizon + ")");
            }
        }

        /** G-11: срок задачи не позже срока её проекта. */
        private void afterProjectDeadline(String at, LocalDate taskDeadline, LocalDate projectDeadline) {
            if (projectDeadline != null && taskDeadline != null && taskDeadline.isAfter(projectDeadline)) {
                violations.add(at + ": AFTER_PROJECT_DEADLINE(" + projectDeadline + ")");
            }
        }
    }
}
