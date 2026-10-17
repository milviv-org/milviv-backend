package com.milviv.unit.application.ai;

import com.milviv.application.ai.contract.PlanDraft.Update;
import com.milviv.application.ai.contract.PlanDraft.TaskUpdate;
import com.milviv.application.ai.contract.PlanDraft.TaskSet;
import com.milviv.application.ai.contract.PlanDraft.RewardUpdate;
import com.milviv.application.ai.contract.PlanDraft.RewardSet;
import com.milviv.application.ai.contract.PlanDraft.ProjectUpdate;
import com.milviv.application.ai.contract.PlanDraft.ProjectSet;
import com.milviv.application.ai.contract.PlanDraft.GoalUpdate;
import com.milviv.application.ai.contract.PlanDraft.GoalSet;
import com.milviv.application.ai.contract.PlanDraft.Delete;
import com.milviv.domain.ai.FieldChange;
import java.util.UUID;
import com.milviv.domain.ai.OperationReport.EntityType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiPlanPolicy;
import com.milviv.application.ai.AiPlanPolicy.Verdict;
import com.milviv.application.ai.HandleRegistry;
import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.application.ai.contract.PlanDraft.Create;
import com.milviv.application.ai.contract.PlanDraft.GoalNode;
import com.milviv.application.ai.contract.PlanDraft.ProjectNode;
import com.milviv.application.ai.contract.PlanDraft.RewardNode;
import com.milviv.application.ai.contract.PlanDraft.TaskNode;
import com.milviv.domain.task.Difficulty;

class AiPlanPolicyTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-11");
    private final AiPlanPolicy policy = new AiPlanPolicy(AiTurnValidatorTest.limits(4, Duration.ofSeconds(90)));

    static TaskNode task(String title) {
        return new TaskNode(title, null, Difficulty.NOVICE, null, null, null);
    }

    private Verdict check(Create create) {
        return policy.check(new PlanDraft(create), TODAY, new HandleRegistry());
    }

    private static Create tasksOnly(List<TaskNode> tasks) {
        return new Create(null, null, tasks, null);
    }

    @Test
    void validTree_passes_andNullListsAreEmpty() {
        Create create = new Create(
                new GoalNode(null, "Английский", null,
                        List.of(new ProjectNode(null, "Грамматика", null, TODAY.plusDays(30), List.of(task("Simple")))),
                        null),
                null, List.of(task("Одиночная")), List.of(new RewardNode("Кино", null, 500)));

        assertThat(check(create).violations()).isEmpty();
    }

    @Test
    void emptyPlan_andMissingCreate_areViolations() {
        assertThat(check(new Create(null, List.of(), null, null)).violations()).containsExactly("plan: EMPTY");
        assertThat(policy.check(null, TODAY, new HandleRegistry()).violations()).containsExactly("plan: REQUIRED");
        assertThat(policy.check(new PlanDraft(null), TODAY, new HandleRegistry()).violations()).containsExactly("plan: EMPTY");
    }

    @Test
    void fields_areChecked_withPaths() {
        TaskNode bad = new TaskNode(" ", "x".repeat(1001), null, 1001, TODAY.plusDays(5), TODAY.plusDays(1));
        Verdict verdict = check(new Create(null, List.of(new ProjectNode(null, "P", null, null, List.of(bad))), null,
                List.of(new RewardNode("R", null, 100_001), new RewardNode("R2", null, null))));

        assertThat(verdict.tooLarge()).isFalse();
        assertThat(verdict.violations()).containsExactlyInAnyOrder(
                "plan.create.projects[0].tasks[0].title: REQUIRED",
                "plan.create.projects[0].tasks[0].description: MAX_LENGTH(1000)",
                "plan.create.projects[0].tasks[0].difficulty: REQUIRED",
                "plan.create.projects[0].tasks[0].points: RANGE(1..1000)",
                "plan.create.projects[0].tasks[0].startDate: AFTER_DEADLINE",
                "plan.create.rewards[0].cost: RANGE(1..100000)",
                "plan.create.rewards[1].cost: REQUIRED");
    }

    @Test
    void dates_pastIsAllowed_horizonIsNot_taskNotAfterProject() {
        TaskNode past = new TaskNode("Прошлое", null, Difficulty.NOVICE, null, TODAY.minusYears(1), TODAY.minusDays(1));
        TaskNode late = new TaskNode("Поздно", null, Difficulty.NOVICE, null, null, TODAY.plusDays(40));
        Verdict verdict = check(new Create(null,
                List.of(new ProjectNode(null, "P", null, TODAY.plusDays(30), List.of(past, late))),
                List.of(new TaskNode("Далеко", null, Difficulty.NOVICE, null, null, TODAY.plusYears(2).plusDays(1))),
                null));

        assertThat(verdict.violations()).containsExactlyInAnyOrder(
                "plan.create.projects[0].tasks[1].deadline: AFTER_PROJECT_DEADLINE(2026-11-10)",
                "plan.create.tasks[0].deadline: AFTER_HORIZON(2028-10-11)");
    }

    @Test
    void counts_overLimit_areTooLarge() {
        List<TaskNode> eleven = Collections.nCopies(11, task("t"));
        assertThat(check(tasksOnly(eleven)).tooLarge()).isTrue();

        List<ProjectNode> elevenProjects = Collections.nCopies(11, new ProjectNode(null, "p", null, null, null));
        assertThat(check(new Create(null, elevenProjects, null, null)).violations())
                .contains("plan.create: PROJECTS_MAX(10)");

        // 4 проекта по 10 задач = 40 задач > 30
        List<ProjectNode> full = Collections.nCopies(4, new ProjectNode(null, "p", null, null, Collections.nCopies(10, task("t"))));
        Verdict verdict = check(new Create(null, full, null, null));
        assertThat(verdict.tooLarge()).isTrue();
        assertThat(verdict.violations()).contains("plan.create: TASKS_MAX(30)", "plan.create: CREATES_MAX(40)");

        List<RewardNode> rewards = Collections.nCopies(4, new RewardNode("r", null, 1));
        assertThat(check(new Create(null, null, null, rewards)).tooLarge()).isTrue();
    }

    // ─── existing (9.4) ───────────────────────────────────────────────────────

    @Test
    void existing_mustBeIssuedHandle_ofRightType_withoutFields() {
        HandleRegistry registry = new HandleRegistry();
        String goal = registry.issue(EntityType.GOAL, java.util.UUID.randomUUID(), 0, null);
        String project = registry.issue(EntityType.PROJECT, java.util.UUID.randomUUID(), 0, TODAY.plusDays(10));

        Create ok = new Create(new GoalNode(goal, null, null, null, List.of(task("В цель"))),
                List.of(new ProjectNode(project, null, null, null, List.of(task("В проект")))), null, null);
        assertThat(policy.check(new PlanDraft(ok), TODAY, registry).violations()).isEmpty();

        Create bad = new Create(new GoalNode(project, "Название", null, null, List.of(task("t"))),
                List.of(new ProjectNode("p_none", null, null, null, List.of(task("t")))), null, null);
        assertThat(policy.check(new PlanDraft(bad), TODAY, registry).violations()).containsExactlyInAnyOrder(
                "plan.create.goal.existing: UNKNOWN_HANDLE",
                "plan.create.goal: EXISTING_WITH_FIELDS",
                "plan.create.projects[0].existing: UNKNOWN_HANDLE");
    }

    @Test
    void existingProject_insideGoal_isNotAllowed() {
        HandleRegistry registry = new HandleRegistry();
        String project = registry.issue(EntityType.PROJECT, java.util.UUID.randomUUID(), 0, null);

        Create create = new Create(new GoalNode(null, "Цель", null,
                List.of(new ProjectNode(project, null, null, null, List.of(task("t")))), null), null, null, null);
        assertThat(policy.check(new PlanDraft(create), TODAY, registry).violations())
                .containsExactly("plan.create.goal.projects[0].existing: NOT_ALLOWED");
    }

    @Test
    void newTask_inExistingProject_respectsDeadlineReadFromCatalog() {
        HandleRegistry registry = new HandleRegistry();
        String project = registry.issue(EntityType.PROJECT, java.util.UUID.randomUUID(), 0, TODAY.plusDays(10));
        TaskNode late = new TaskNode("Поздно", null, Difficulty.NOVICE, null, null, TODAY.plusDays(11));

        Create create = new Create(null, List.of(new ProjectNode(project, null, null, null, List.of(late))), null, null);
        assertThat(policy.check(new PlanDraft(create), TODAY, registry).violations())
                .containsExactly("plan.create.projects[0].tasks[0].deadline: AFTER_PROJECT_DEADLINE(2026-10-21)");
    }

    @Test
    void existingNodes_doNotCountAsCreates() {
        HandleRegistry registry = new HandleRegistry();
        List<ProjectNode> existing = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            existing.add(new ProjectNode(registry.issue(EntityType.PROJECT, java.util.UUID.randomUUID(), 0, null),
                    null, null, null, List.of(task("t"))));
        }
        // 11 существующих проектов — не превышение PROJECTS_MAX(10): создаются только задачи
        assertThat(policy.check(new PlanDraft(new Create(null, existing, null, null)), TODAY, registry).tooLarge()).isFalse();
    }

    // ─── Изменения и удаления (9.5) ───────────────────────────────────────────

    private static <T> FieldChange<T> to(T value) {
        return new FieldChange<>(value);
    }

    private static TaskSet taskSet(FieldChange<String> title, FieldChange<LocalDate> deadline, FieldChange<String> project) {
        return new TaskSet(title, null, null, null, null, deadline, project, null);
    }

    private static ProjectSet deadline(LocalDate deadline) {
        return new ProjectSet(null, null, to(deadline), null);
    }

    private Verdict changes(HandleRegistry registry, Create create, Update update, List<Delete> deletes) {
        return policy.check(new PlanDraft(create, update, deletes), TODAY, registry);
    }

    private static Update tasks(TaskUpdate... updates) {
        return new Update(List.of(updates), null, null, null);
    }

    @Test
    void planWithOnlyChanges_passes() {
        HandleRegistry registry = new HandleRegistry();
        String task = registry.issue(EntityType.TASK, UUID.randomUUID(), 0, null, null);
        String reward = registry.issue(EntityType.REWARD, UUID.randomUUID(), 0, null);

        Update update = new Update(List.of(new TaskUpdate(task, taskSet(to("Новое"), null, null))), null, null,
                List.of(new RewardUpdate(reward, new RewardSet(null, null, to(500)))));
        assertThat(changes(registry, null, update, null).violations()).isEmpty();
    }

    @Test
    void targets_mustBeIssued_unique_andNotReferencedWhenDeleted() {
        HandleRegistry registry = new HandleRegistry();
        String task = registry.issue(EntityType.TASK, UUID.randomUUID(), 0, null, null);
        String project = registry.issue(EntityType.PROJECT, UUID.randomUUID(), 0, null);
        String goal = registry.issue(EntityType.GOAL, UUID.randomUUID(), 0, null);

        Verdict verdict = changes(registry,
                new Create(new GoalNode(goal, null, null, null, List.of(task("В удаляемую цель"))), null, null, null),
                new Update(
                        List.of(new TaskUpdate(task, taskSet(null, null, to(project))),
                                new TaskUpdate(project, taskSet(to("x"), null, null)),
                                new TaskUpdate("t_none", null)),
                        null, null, null),
                List.of(new Delete(project), new Delete(task), new Delete(goal), new Delete("g_none")));

        assertThat(verdict.violations()).containsExactlyInAnyOrder(
                "plan.delete[3].target: UNKNOWN_HANDLE",
                "plan.delete[0].target: DUPLICATE_TARGET",
                "plan.delete[1].target: DUPLICATE_TARGET",
                "plan.create.goal.existing: REFERENCES_DELETED",
                "plan.update.tasks[0].set.project: REFERENCES_DELETED",
                "plan.update.tasks[1].target: UNKNOWN_HANDLE",
                "plan.update.tasks[2].target: UNKNOWN_HANDLE",
                "plan.update.tasks[2].set: EMPTY_SET");
    }

    @Test
    void set_fields_areChecked() {
        HandleRegistry registry = new HandleRegistry();
        String task = registry.issue(EntityType.TASK, UUID.randomUUID(), 0, null, null);
        String goal = registry.issue(EntityType.GOAL, UUID.randomUUID(), 0, null);
        String reward = registry.issue(EntityType.REWARD, UUID.randomUUID(), 0, null);

        Update update = new Update(
                List.of(new TaskUpdate(task, new TaskSet(to(" "), to("x".repeat(1001)), to(null), to(1001),
                        to(TODAY.plusDays(5)), to(TODAY.plusDays(1)), null, null))),
                null,
                List.of(new GoalUpdate(goal, new GoalSet(null, null))),
                List.of(new RewardUpdate(reward, new RewardSet(null, null, to(null)))));

        assertThat(changes(registry, null, update, null).violations()).containsExactlyInAnyOrder(
                "plan.update.tasks[0].set.title: REQUIRED",
                "plan.update.tasks[0].set.description: MAX_LENGTH(1000)",
                "plan.update.tasks[0].set.difficulty: REQUIRED",
                "plan.update.tasks[0].set.points: RANGE(1..1000)",
                "plan.update.tasks[0].set.startDate: AFTER_DEADLINE",
                "plan.update.goals[0].set: EMPTY_SET",
                "plan.update.rewards[0].set.cost: REQUIRED");
    }

    @Test
    void refs_nameNewProjects_forMovingTasks() {
        HandleRegistry registry = new HandleRegistry();
        String task = registry.issue(EntityType.TASK, UUID.randomUUID(), 0, null, null);
        String other = registry.issue(EntityType.TASK, UUID.randomUUID(), 0, null, null);
        String project = registry.issue(EntityType.PROJECT, UUID.randomUUID(), 0, null);

        Create create = new Create(null, List.of(
                new ProjectNode("n_read", null, "Чтение", null, null, null),
                new ProjectNode("n_read", null, "Повтор", null, null, null),
                new ProjectNode("Bad-Ref", null, "Плохой", null, null, null),
                new ProjectNode("n_x", project, null, null, null, null)), null, null);
        Update update = tasks(new TaskUpdate(task, taskSet(null, null, to("n_read"))),
                new TaskUpdate(other, taskSet(null, null, to("n_none"))));

        assertThat(changes(registry, create, update, null).violations()).containsExactlyInAnyOrder(
                "plan.create.projects[1].ref: DUPLICATE_REF",
                "plan.create.projects[2].ref: INVALID_REF",
                "plan.create.projects[3]: EXISTING_WITH_FIELDS",
                "plan.update.tasks[1].set.project: UNKNOWN_REF");
    }

    @Test
    void tooManyUpdatesOrDeletes_isTooLarge() {
        HandleRegistry registry = new HandleRegistry();
        List<TaskUpdate> updates = new java.util.ArrayList<>();
        List<Delete> deletes = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            updates.add(new TaskUpdate(registry.issue(EntityType.TASK, UUID.randomUUID(), 0, null, null), taskSet(to("t"), null, null)));
        }
        for (int i = 0; i < 11; i++) {
            deletes.add(new Delete(registry.issue(EntityType.GOAL, UUID.randomUUID(), 0, null)));
        }

        Verdict verdict = changes(registry, null, new Update(updates, null, null, null), deletes);
        assertThat(verdict.tooLarge()).isTrue();
        assertThat(verdict.violations()).containsExactly("plan.update: UPDATES_MAX(20)", "plan.delete: DELETES_MAX(10)");
    }

    @Test
    void dates_areCheckedOnFinalState_ofWhatAiRead() {
        HandleRegistry registry = new HandleRegistry();
        UUID projectId = UUID.randomUUID();
        String project = registry.issue(EntityType.PROJECT, projectId, 0, TODAY.plusDays(30));
        String early = registry.issue(EntityType.PROJECT, UUID.randomUUID(), 0, TODAY.plusDays(5));
        String task = registry.issue(EntityType.TASK, UUID.randomUUID(), 0, TODAY.plusDays(20), projectId);

        // перенос задачи в проект с более ранним сроком
        assertThat(changes(registry, null, tasks(new TaskUpdate(task, taskSet(null, null, to(early)))), null).violations())
                .containsExactly("plan.update.tasks[0].set.deadline: AFTER_PROJECT_DEADLINE(" + TODAY.plusDays(5) + ")");

        // сокращение срока проекта ниже срока известной задачи
        Update shrink = new Update(null, List.of(new ProjectUpdate(project, deadline(TODAY.plusDays(10)))), null, null);
        assertThat(changes(registry, null, shrink, null).violations())
                .containsExactly("plan.update.projects[0].set.deadline: BEFORE_TASK_DEADLINE(" + TODAY.plusDays(20) + ")");

        // то же, но задаче в этом же плане срок сдвинут раньше — или она удаляется
        Update both = new Update(List.of(new TaskUpdate(task, taskSet(null, to(TODAY.plusDays(8)), null))),
                List.of(new ProjectUpdate(project, deadline(TODAY.plusDays(10)))), null, null);
        assertThat(changes(registry, null, both, null).violations()).isEmpty();
        assertThat(changes(registry, null, shrink, List.of(new Delete(task))).violations()).isEmpty();
    }
}
