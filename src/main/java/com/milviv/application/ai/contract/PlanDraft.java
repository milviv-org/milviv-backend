package com.milviv.application.ai.contract;

import java.time.LocalDate;
import java.util.List;

import com.milviv.domain.ai.FieldChange;
import com.milviv.domain.task.Difficulty;

/**
 * Черновик плана от AI, PlanDraft v1 (§6.5). НЕДОВЕРЕННЫЙ: только через AiPlanPolicy.
 *
 * Три части:
 * - create — дерево: висячих ссылок и циклов между новыми сущностями нет по построению;
 *   existing — дескриптор существующей цели или проекта, новые дети создаются в нём;
 * - update — списки по типам, target — дескриптор, set — только поля из белого списка;
 * - delete — дескрипторы.
 * Изменения и удаления исполняются только после согласия пользователя (G-3).
 * Списки могут прийти null — читаются через {@link #list(List)}.
 */
public record PlanDraft(Create create, Update update, List<Delete> delete) {

    /** Только создания. */
    public PlanDraft(Create create) {
        this(create, null, null);
    }

    /** @param goal не больше одной цели */
    public record Create(GoalNode goal, List<ProjectNode> projects, List<TaskNode> tasks, List<RewardNode> rewards) {}

    /** @param existing g_* — добавить детей в существующую цель; тогда title и description — null */
    public record GoalNode(String existing, String title, String description, List<ProjectNode> projects,
                           List<TaskNode> tasks) {}

    /**
     * @param ref      n_* — имя нового проекта, чтобы перенести в него существующие задачи (set.project)
     * @param existing p_* — добавить задачи в существующий проект; тогда ref, title, description, deadline — null.
     *                 Только в create.projects: перенос существующего проекта в цель — изменение
     */
    public record ProjectNode(String ref, String existing, String title, String description, LocalDate deadline,
                              List<TaskNode> tasks) {

        public ProjectNode(String existing, String title, String description, LocalDate deadline, List<TaskNode> tasks) {
            this(null, existing, title, description, deadline, tasks);
        }
    }

    /** @param points null — по сложности */
    public record TaskNode(String title, String description, Difficulty difficulty, Integer points,
                           LocalDate startDate, LocalDate deadline) {}

    /** Скрытость не задаётся: AI скрытые награды не создаёт (G-5). */
    public record RewardNode(String title, String description, Integer cost) {}

    // ─── Изменения ────────────────────────────────────────────────────────────

    public record Update(List<TaskUpdate> tasks, List<ProjectUpdate> projects, List<GoalUpdate> goals,
                         List<RewardUpdate> rewards) {}

    public record TaskUpdate(String target, TaskSet set) {}

    /**
     * Статус, дата выполнения, напоминание через AI не меняются (G-16).
     *
     * @param project p_* или n_*; FieldChange(null) — убрать из проекта
     * @param goal    g_*; FieldChange(null) — убрать из цели
     */
    public record TaskSet(FieldChange<String> title, FieldChange<String> description, FieldChange<Difficulty> difficulty,
                          FieldChange<Integer> points, FieldChange<LocalDate> startDate, FieldChange<LocalDate> deadline,
                          FieldChange<String> project, FieldChange<String> goal) {}

    public record ProjectUpdate(String target, ProjectSet set) {}

    /** Статус и сложность через AI не меняются: сложность вычисляет система. */
    public record ProjectSet(FieldChange<String> title, FieldChange<String> description,
                             FieldChange<LocalDate> deadline, FieldChange<String> goal) {}

    public record GoalUpdate(String target, GoalSet set) {}

    public record GoalSet(FieldChange<String> title, FieldChange<String> description) {}

    public record RewardUpdate(String target, RewardSet set) {}

    /** Скрытость, пароль и обмен баллов AI недоступны (G-5, G-16). */
    public record RewardSet(FieldChange<String> title, FieldChange<String> description, FieldChange<Integer> cost) {}

    /** @param target g_*, p_*, t_*, r_* */
    public record Delete(String target) {}

    public static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items;
    }

    public List<Delete> deletes() {
        return list(delete);
    }

    public boolean hasChanges() {
        return !deletes().isEmpty() || (update != null && (!list(update.tasks()).isEmpty()
                || !list(update.projects()).isEmpty() || !list(update.goals()).isEmpty()
                || !list(update.rewards()).isEmpty()));
    }
}
