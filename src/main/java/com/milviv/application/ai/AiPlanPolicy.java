package com.milviv.application.ai;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.application.ai.contract.PlanDraft.GoalNode;
import com.milviv.application.ai.contract.PlanDraft.ProjectNode;
import com.milviv.application.ai.contract.PlanDraft.RewardNode;
import com.milviv.application.ai.contract.PlanDraft.TaskNode;
import com.milviv.domain.ai.AiHandle;
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
 * existing — только дескрипторы, выданные каталогом в этом запросе, подходящего типа (§6.4).
 */
public class AiPlanPolicy {

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
        if (plan == null || plan.create() == null) {
            return new Verdict(List.of("plan.create: REQUIRED"), false);
        }
        PlanDraft.Create create = plan.create();
        if (isEmpty(create)) {
            return new Verdict(List.of("plan.create: EMPTY"), false);
        }

        List<String> tooLarge = counts(create);
        if (!tooLarge.isEmpty()) {
            return new Verdict(tooLarge, true);
        }

        List<String> violations = new ArrayList<>();
        LocalDate horizon = today.plusYears(limits.dateHorizonYears());
        if (create.goal() != null) {
            GoalNode goal = create.goal();
            String at = "plan.create.goal";
            if (goal.existing() != null) {
                existing(violations, at, goal.existing(), EntityType.GOAL, registry);
                if (goal.title() != null || goal.description() != null) {
                    violations.add(at + ": EXISTING_WITH_FIELDS");
                }
            } else {
                texts(violations, at, goal.title(), goal.description());
            }
            List<ProjectNode> goalProjects = list(goal.projects());
            for (int i = 0; i < goalProjects.size(); i++) {
                // существующий проект в цель — это изменение проекта, оно требует согласия (9.5)
                if (goalProjects.get(i).existing() != null) {
                    violations.add(at + ".projects[" + i + "].existing: NOT_ALLOWED");
                }
            }
            projects(violations, at + ".projects", goalProjects, horizon, registry);
            tasks(violations, at + ".tasks", list(goal.tasks()), null, horizon);
        }
        projects(violations, "plan.create.projects", list(create.projects()), horizon, registry);
        tasks(violations, "plan.create.tasks", list(create.tasks()), null, horizon);
        rewards(violations, list(create.rewards()));
        return new Verdict(violations, false);
    }

    // ─── Количества ───────────────────────────────────────────────────────────

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

    // ─── Узлы ─────────────────────────────────────────────────────────────────

    private void projects(List<String> violations, String path, List<ProjectNode> projects, LocalDate horizon,
                          HandleRegistry registry) {
        for (int i = 0; i < projects.size(); i++) {
            ProjectNode project = projects.get(i);
            String at = path + "[" + i + "]";
            LocalDate deadline;
            if (project.existing() != null) {
                Optional<AiHandle> handle = existing(violations, at, project.existing(), EntityType.PROJECT, registry);
                if (project.title() != null || project.description() != null || project.deadline() != null) {
                    violations.add(at + ": EXISTING_WITH_FIELDS");
                }
                // срок существующего проекта — запомненный при чтении; домен перепроверит при исполнении
                deadline = handle.map(AiHandle::deadline).orElse(null);
            } else {
                texts(violations, at, project.title(), project.description());
                date(violations, at + ".deadline", project.deadline(), horizon);
                deadline = project.deadline();
            }
            tasks(violations, at + ".tasks", list(project.tasks()), deadline, horizon);
        }
    }

    private static Optional<AiHandle> existing(List<String> violations, String at, String handle, EntityType type,
                                               HandleRegistry registry) {
        Optional<AiHandle> resolved = registry.resolve(handle, type);
        if (resolved.isEmpty()) {
            violations.add(at + ".existing: UNKNOWN_HANDLE");
        }
        return resolved;
    }

    /** @param projectDeadline срок проекта этих задач; null — проекта нет или срока нет */
    private void tasks(List<String> violations, String path, List<TaskNode> tasks, LocalDate projectDeadline,
                       LocalDate horizon) {
        for (int i = 0; i < tasks.size(); i++) {
            TaskNode task = tasks.get(i);
            String at = path + "[" + i + "]";
            texts(violations, at, task.title(), task.description());
            if (task.difficulty() == null) {
                violations.add(at + ".difficulty: REQUIRED");
            }
            if (task.points() != null && (task.points() < Task.MIN_POINTS || task.points() > Task.MAX_POINTS)) {
                violations.add(at + ".points: RANGE(" + Task.MIN_POINTS + ".." + Task.MAX_POINTS + ")");
            }
            date(violations, at + ".startDate", task.startDate(), horizon);
            date(violations, at + ".deadline", task.deadline(), horizon);
            if (task.startDate() != null && task.deadline() != null && task.startDate().isAfter(task.deadline())) {
                violations.add(at + ".startDate: AFTER_DEADLINE");
            }
            // G-11: срок задачи не позже срока её проекта
            if (projectDeadline != null && task.deadline() != null && task.deadline().isAfter(projectDeadline)) {
                violations.add(at + ".deadline: AFTER_PROJECT_DEADLINE(" + projectDeadline + ")");
            }
        }
    }

    private void rewards(List<String> violations, List<RewardNode> rewards) {
        for (int i = 0; i < rewards.size(); i++) {
            RewardNode reward = rewards.get(i);
            String at = "plan.create.rewards[" + i + "]";
            texts(violations, at, reward.title(), reward.description());
            if (reward.cost() == null) {
                violations.add(at + ".cost: REQUIRED");
            } else if (reward.cost() < 1 || reward.cost() > limits.rewardCostMax()) {
                violations.add(at + ".cost: RANGE(1.." + limits.rewardCostMax() + ")");
            }
        }
    }

    private void texts(List<String> violations, String at, String title, String description) {
        if (title == null || title.isBlank()) {
            violations.add(at + ".title: REQUIRED");
        } else if (title.length() > limits.titleMaxLength()) {
            violations.add(at + ".title: MAX_LENGTH(" + limits.titleMaxLength() + ")");
        }
        if (description != null && description.length() > limits.descriptionMaxLength()) {
            violations.add(at + ".description: MAX_LENGTH(" + limits.descriptionMaxLength() + ")");
        }
    }

    /** В прошлом можно (G-23); дальше горизонта — нет. */
    private static void date(List<String> violations, String at, LocalDate date, LocalDate horizon) {
        if (date != null && date.isAfter(horizon)) {
            violations.add(at + ": AFTER_HORIZON(" + horizon + ")");
        }
    }
}
