package com.milviv.application.ai;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.milviv.application.gamification.RewardCommands;
import com.milviv.application.gamification.RewardView;
import com.milviv.application.goal.GoalCommands;
import com.milviv.application.goal.GoalView;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.project.ProjectView;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.exception.NotFoundException;

/**
 * Текущее состояние цели изменения или удаления — через ручные сценарии, с user_id из сессии.
 * Скрытая награда загружается без пароля, поэтому для AI её нет (G-5).
 */
final class AiTargets {

    /** @param deadline срок проекта; у остальных null */
    record Current(long version, String title, LocalDate deadline) {}

    private final GoalCommands goals;
    private final ProjectCommands projects;
    private final TaskCommands tasks;
    private final RewardCommands rewards;

    AiTargets(GoalCommands goals, ProjectCommands projects, TaskCommands tasks, RewardCommands rewards) {
        this.goals = goals;
        this.projects = projects;
        this.tasks = tasks;
        this.rewards = rewards;
    }

    /** Пусто — сущность удалена, чужая или стала скрытой наградой. */
    Optional<Current> find(UUID userId, EntityType type, UUID id) {
        try {
            return Optional.of(switch (type) {
                case GOAL -> {
                    GoalView goal = goals.get(userId, id);
                    yield new Current(goal.version(), goal.title(), null);
                }
                case PROJECT -> {
                    ProjectView project = projects.get(userId, id);
                    yield new Current(project.version(), project.title(), project.deadline());
                }
                case TASK -> {
                    TaskView task = tasks.get(userId, id);
                    yield new Current(task.version(), task.title(), null);
                }
                case REWARD -> {
                    RewardView reward = rewards.get(userId, id, null);
                    yield new Current(reward.version(), reward.title(), null);
                }
            });
        } catch (NotFoundException ex) {
            return Optional.empty();
        }
    }
}
