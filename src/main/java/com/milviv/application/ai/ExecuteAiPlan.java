package com.milviv.application.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.milviv.domain.ai.OperationReport;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.ai.OperationReport.Item;
import com.milviv.domain.ai.OperationReport.Result;
import com.milviv.domain.gamification.RewardDetails;
import com.milviv.domain.goal.GoalDetails;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.task.TaskDetails;

import static com.milviv.application.ai.contract.PlanDraft.list;

/**
 * Исполнение плана AI через ручные сценарии (принцип 5): правила домена те же, что у ручного API,
 * userId — из сессии, а не от AI. Отчёт строится из того, что вернули сценарии (инвариант 6).
 *
 * Вызывается только внутри транзакции HandleAiRequest — всё или ничего (§10.1).
 */
public class ExecuteAiPlan {

    private final GoalCommands goals;
    private final ProjectCommands projects;
    private final TaskCommands tasks;
    private final RewardCommands rewards;
    private final TransactionManager transactionManager;

    public ExecuteAiPlan(GoalCommands goals, ProjectCommands projects, TaskCommands tasks, RewardCommands rewards,
                         TransactionManager transactionManager) {
        this.goals = goals;
        this.projects = projects;
        this.tasks = tasks;
        this.rewards = rewards;
        this.transactionManager = transactionManager;
    }

    /**
     * Порядок: цель → её проекты → их задачи → задачи цели → проекты без цели → их задачи →
     * задачи без проекта → награды. Родитель всегда создан раньше детей.
     */
    public OperationReport executeCreations(UUID userId, PlanDraft.Create create) {
        // MANDATORY: без внешней транзакции частичное создание было бы возможно
        return transactionManager.execute(Propagation.MANDATORY, () -> {
            List<Item> report = new ArrayList<>();
            GoalNode goalNode = create.goal();
            if (goalNode != null) {
                GoalView goal = goals.create(userId, new GoalDetails(goalNode.title(), goalNode.description()));
                report.add(new Item("g1", Result.CREATED, EntityType.GOAL, goal.id(), goal.title()));
                createProjects(userId, "g1.p", list(goalNode.projects()), goal.id(), report);
                createTasks(userId, "g1.t", list(goalNode.tasks()), null, goal.id(), report);
            }
            createProjects(userId, "p", list(create.projects()), null, report);
            createTasks(userId, "t", list(create.tasks()), null, null, report);

            List<RewardNode> rewardNodes = list(create.rewards());
            for (int i = 0; i < rewardNodes.size(); i++) {
                RewardNode node = rewardNodes.get(i);
                // скрытые награды AI не создаёт (G-5), пароль не нужен
                RewardView reward = rewards.create(userId, new RewardDetails(node.title(), node.description(), node.cost(), false), null);
                report.add(new Item("r" + (i + 1), Result.CREATED, EntityType.REWARD, reward.id(), reward.title()));
            }
            return new OperationReport(report);
        });
    }

    private void createProjects(UUID userId, String prefix, List<ProjectNode> nodes, UUID goalId, List<Item> report) {
        for (int i = 0; i < nodes.size(); i++) {
            ProjectNode node = nodes.get(i);
            String itemId = prefix + (i + 1);
            ProjectView project = projects.create(userId,
                    new ProjectDetails(goalId, node.title(), node.description(), node.deadline()));
            report.add(new Item(itemId, Result.CREATED, EntityType.PROJECT, project.id(), project.title()));
            createTasks(userId, itemId + ".t", list(node.tasks()), project.id(), null, report);
        }
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
}
