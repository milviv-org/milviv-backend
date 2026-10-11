package com.milviv.application.ai.contract;

import java.time.LocalDate;
import java.util.List;

import com.milviv.domain.task.Difficulty;

/**
 * Черновик плана от AI, PlanDraft v1 (§6.5). НЕДОВЕРЕННЫЙ: только через AiPlanPolicy.
 *
 * Сейчас — только создание, деревом: висячих ссылок и циклов между новыми сущностями нет по построению.
 * Ссылки на существующие сущности (existing, ref) и списки update/delete добавятся в 9.4–9.5.
 * Списки могут прийти null — читаются через {@link #list(List)}.
 */
public record PlanDraft(Create create) {

    /** @param goal не больше одной цели */
    public record Create(GoalNode goal, List<ProjectNode> projects, List<TaskNode> tasks, List<RewardNode> rewards) {}

    public record GoalNode(String title, String description, List<ProjectNode> projects, List<TaskNode> tasks) {}

    public record ProjectNode(String title, String description, LocalDate deadline, List<TaskNode> tasks) {}

    /** @param points null — по сложности */
    public record TaskNode(String title, String description, Difficulty difficulty, Integer points,
                           LocalDate startDate, LocalDate deadline) {}

    /** Скрытость не задаётся: AI скрытые награды не создаёт (G-5). */
    public record RewardNode(String title, String description, Integer cost) {}

    public static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items;
    }
}
