package com.milviv.unit.application.ai;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiPlanPolicy;
import com.milviv.application.ai.AiPlanPolicy.Verdict;
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
        return policy.check(new PlanDraft(create), TODAY);
    }

    private static Create tasksOnly(List<TaskNode> tasks) {
        return new Create(null, null, tasks, null);
    }

    @Test
    void validTree_passes_andNullListsAreEmpty() {
        Create create = new Create(
                new GoalNode("Английский", null,
                        List.of(new ProjectNode("Грамматика", null, TODAY.plusDays(30), List.of(task("Simple")))),
                        null),
                null, List.of(task("Одиночная")), List.of(new RewardNode("Кино", null, 500)));

        assertThat(check(create).violations()).isEmpty();
    }

    @Test
    void emptyPlan_andMissingCreate_areViolations() {
        assertThat(check(new Create(null, List.of(), null, null)).violations()).containsExactly("plan.create: EMPTY");
        assertThat(policy.check(new PlanDraft(null), TODAY).violations()).containsExactly("plan.create: REQUIRED");
    }

    @Test
    void fields_areChecked_withPaths() {
        TaskNode bad = new TaskNode(" ", "x".repeat(1001), null, 1001, TODAY.plusDays(5), TODAY.plusDays(1));
        Verdict verdict = check(new Create(null, List.of(new ProjectNode("P", null, null, List.of(bad))), null,
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
                List.of(new ProjectNode("P", null, TODAY.plusDays(30), List.of(past, late))),
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

        List<ProjectNode> elevenProjects = Collections.nCopies(11, new ProjectNode("p", null, null, null));
        assertThat(check(new Create(null, elevenProjects, null, null)).violations())
                .contains("plan.create: PROJECTS_MAX(10)");

        // 4 проекта по 10 задач = 40 задач > 30
        List<ProjectNode> full = Collections.nCopies(4, new ProjectNode("p", null, null, Collections.nCopies(10, task("t"))));
        Verdict verdict = check(new Create(null, full, null, null));
        assertThat(verdict.tooLarge()).isTrue();
        assertThat(verdict.violations()).contains("plan.create: TASKS_MAX(30)", "plan.create: CREATES_MAX(40)");

        List<RewardNode> rewards = Collections.nCopies(4, new RewardNode("r", null, 1));
        assertThat(check(new Create(null, null, null, rewards)).tooLarge()).isTrue();
    }
}
