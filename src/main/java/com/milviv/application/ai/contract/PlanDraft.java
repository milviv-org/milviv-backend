package com.milviv.application.ai.contract;

import java.time.LocalDate;
import java.util.List;

import com.milviv.domain.task.Difficulty;

/**
 * Черновик плана от AI, PlanDraft v1 (§6.5). НЕДОВЕРЕННЫЙ: только через AiPlanPolicy.
 *
 * Сейчас — только создание, деревом: висячих ссылок и циклов между новыми сущностями нет по построению.
 * existing — дескриптор существующей цели или проекта: новые дети создаются в нём.
 * ref и списки update/delete добавятся в 9.5.
 * Списки могут прийти null — читаются через {@link #list(List)}.
 */
public record PlanDraft(Create create) {

    /** @param goal не больше одной цели */
    public record Create(GoalNode goal, List<ProjectNode> projects, List<TaskNode> tasks, List<RewardNode> rewards) {}

    /** @param existing g_* — добавить детей в существующую цель; тогда title и description — null */
    public record GoalNode(String existing, String title, String description, List<ProjectNode> projects,
                           List<TaskNode> tasks) {}

    /**
     * @param existing p_* — добавить задачи в существующий проект; тогда title, description, deadline — null.
     *                 Только в create.projects: перенос существующего проекта в цель — изменение (9.5)
     */
    public record ProjectNode(String existing, String title, String description, LocalDate deadline,
                              List<TaskNode> tasks) {}

    /** @param points null — по сложности */
    public record TaskNode(String title, String description, Difficulty difficulty, Integer points,
                           LocalDate startDate, LocalDate deadline) {}

    /** Скрытость не задаётся: AI скрытые награды не создаёт (G-5). */
    public record RewardNode(String title, String description, Integer cost) {}

    public static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items;
    }
}
