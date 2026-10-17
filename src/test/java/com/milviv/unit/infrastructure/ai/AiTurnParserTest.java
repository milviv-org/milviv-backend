package com.milviv.unit.infrastructure.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiProviderException;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.DataQuery;
import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.domain.ai.FieldChange;
import com.milviv.domain.ai.RefusalReason;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskStatus;
import com.milviv.infrastructure.ai.AiTurnParser;

/** Строгий разбор хода AI (§6.2): фикстуры каждого исхода и операции, отказы с путями. */
class AiTurnParserTest {

    private final AiTurnParser parser = new AiTurnParser();

    static String fixture(String name) {
        try (InputStream in = AiTurnParserTest.class.getResourceAsStream("/ai/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<String> violations(String json) {
        AiProviderException ex = catchThrowableOfType(AiProviderException.class, () -> parser.parse(json));
        assertThat(ex).isNotNull();
        assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.MALFORMED);
        return ex.getViolations();
    }

    // ─── Фикстуры ─────────────────────────────────────────────────────────────

    @Test
    void textOutcomes() {
        assertThat(parser.parse(fixture("answer.json")))
                .isEqualTo(new AiTurn.Answer("Чаще всего просрочены задачи по грамматике"));
        assertThat(parser.parse(fixture("clarification.json")))
                .isEqualTo(new AiTurn.ClarificationNeeded("К какому сроку нужно выучить английский?"));
        assertThat(parser.parse(fixture("refused.json")))
                .isEqualTo(new AiTurn.Refused(RefusalReason.UNSUPPORTED_OPERATION));
    }

    @Test
    void dataRequest_allQueries() {
        AiTurn.DataRequest turn = (AiTurn.DataRequest) parser.parse(fixture("data-request.json"));

        assertThat(turn.dataRequestReason()).isEqualTo("Найти задачи по грамматике");
        assertThat(turn.dataRequests()).containsExactly(
                new DataQuery.ListGoals(),
                new DataQuery.ListProjects("g_k7Qx", ProjectStatus.ACTIVE),
                new DataQuery.ListTasks("p_2mZa", null, null, TaskStatus.ACTIVE,
                        new DataQuery.Period(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))),
                new DataQuery.ListRewards(),
                new DataQuery.ListPlans(new DataQuery.Period(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-24"))),
                new DataQuery.ListReflections("t_9fLm", null));
    }

    @Test
    void proposal_creations() {
        AiTurn.Proposal turn = (AiTurn.Proposal) parser.parse(fixture("proposal-create.json"));
        PlanDraft.Create create = turn.plan().create();

        assertThat(create.goal().title()).isEqualTo("Английский");
        assertThat(create.goal().projects().getFirst().tasks().getFirst())
                .isEqualTo(new PlanDraft.TaskNode("Времена Simple", null, Difficulty.APPRENTICE, null, null,
                        LocalDate.parse("2026-11-01")));
        assertThat(create.projects().getFirst().existing()).isEqualTo("p_2mZa");
        assertThat(create.projects().getFirst().tasks().getFirst().points()).isEqualTo(12);
        assertThat(create.rewards().getFirst().cost()).isEqualTo(500);
        assertThat(turn.plan().hasChanges()).isFalse();
    }

    @Test
    void proposal_changes_distinguishUnchangedFromCleared() {
        AiTurn.Proposal turn = (AiTurn.Proposal) parser.parse(fixture("proposal-changes.json"));
        PlanDraft plan = turn.plan();

        assertThat(turn.answer()).isNotNull();
        assertThat(plan.create().projects().getFirst().ref()).isEqualTo("n_read");
        PlanDraft.TaskSet set = plan.update().tasks().getFirst().set();
        assertThat(set.title()).isNull();                               // не менять
        assertThat(set.description()).isEqualTo(new FieldChange<>(null)); // очистить
        assertThat(set.difficulty()).isEqualTo(new FieldChange<>(Difficulty.EXPERT));
        assertThat(set.deadline()).isEqualTo(new FieldChange<>(LocalDate.parse("2026-10-08")));
        assertThat(set.project()).isEqualTo(new FieldChange<>("n_read"));
        assertThat(plan.update().projects().getFirst().set().goal()).isEqualTo(new FieldChange<>("g_k7Qx"));
        assertThat(plan.update().goals().getFirst().set().title()).isEqualTo(new FieldChange<>("Английский B2"));
        assertThat(plan.update().rewards().getFirst().set().cost()).isEqualTo(new FieldChange<>(700));
        assertThat(plan.deletes()).containsExactly(new PlanDraft.Delete("p_Old1"));
    }

    // ─── Отказы ───────────────────────────────────────────────────────────────

    @Test
    void unknownField_isRejected_withPath() {
        String json = """
                {"contractVersion": "assistant.v1", "outcome": "PROPOSAL", "summary": "s",
                 "plan": {"update": {"tasks": [{"target": "t_1", "set": {"status": {"value": "COMPLETED"}}}]}}}""";
        assertThat(violations(json)).containsExactly("plan.update.tasks[0].set.status: UNKNOWN_FIELD");

        String hidden = """
                {"contractVersion": "assistant.v1", "outcome": "PROPOSAL", "summary": "s",
                 "plan": {"update": {"rewards": [{"target": "r_1", "set": {"hidden": {"value": true}}}]}}}""";
        assertThat(violations(hidden)).containsExactly("plan.update.rewards[0].set.hidden: UNKNOWN_FIELD");

        assertThat(violations("{\"contractVersion\": \"assistant.v1\", \"outcome\": \"ANSWER\", \"answer\": \"a\", \"userId\": 1}"))
                .containsExactly("userId: UNKNOWN_FIELD");
    }

    @Test
    void fieldOfAnotherOutcome_isRejected() {
        String json = """
                {"contractVersion": "assistant.v1", "outcome": "ANSWER", "answer": "a",
                 "plan": {"delete": [{"target": "p_1"}]}}""";
        assertThat(violations(json)).containsExactly("plan: NOT_ALLOWED_FOR_OUTCOME");
    }

    @Test
    void queryParameter_notInCatalog_isRejected() {
        String json = """
                {"contractVersion": "assistant.v1", "outcome": "DATA_REQUEST", "dataRequestReason": "r",
                 "dataRequests": [{"query": "LIST_GOALS", "project": "p_1"}, {"query": "LIST_USERS"},
                                  {"query": "LIST_TASKS", "status": "DONE"}]}""";
        assertThat(violations(json)).containsExactly(
                "dataRequests[0].project: NOT_ALLOWED",
                "dataRequests[1].query: INVALID_VALUE",
                "dataRequests[2].status: INVALID_VALUE");
    }

    @Test
    void typesAreNotCoerced() {
        String points = """
                {"contractVersion": "assistant.v1", "outcome": "PROPOSAL", "summary": "s",
                 "plan": {"create": {"tasks": [{"title": "t", "difficulty": "NOVICE", "points": "5"}]}}}""";
        assertThat(violations(points)).containsExactly("plan.create.tasks[0].points: INVALID_VALUE");

        String difficulty = """
                {"contractVersion": "assistant.v1", "outcome": "PROPOSAL", "summary": "s",
                 "plan": {"create": {"tasks": [{"title": "t", "difficulty": "GODLIKE"}]}}}""";
        assertThat(violations(difficulty)).containsExactly("plan.create.tasks[0].difficulty: INVALID_VALUE");

        // set без обёртки {value}
        String bare = """
                {"contractVersion": "assistant.v1", "outcome": "PROPOSAL", "summary": "s",
                 "plan": {"update": {"goals": [{"target": "g_1", "set": {"title": "Новое"}}]}}}""";
        assertThat(violations(bare)).containsExactly("plan.update.goals[0].set.title: INVALID_VALUE");
    }

    @Test
    void envelopeProblems() {
        assertThat(violations("")).containsExactly("response: EMPTY");
        assertThat(violations("```json\n" + fixture("answer.json") + "```")).containsExactly("response: NOT_JSON");
        assertThat(violations(fixture("answer.json") + " и ещё текст")).containsExactly("response: NOT_JSON");
        assertThat(violations("{\"contractVersion\": \"assistant.v1\", \"outcome\": \"ANSWER\", \"answer\": \"a\", \"answer\": \"b\"}"))
                .containsExactly("response: DUPLICATE_KEY");
        assertThat(violations("[1, 2]")).containsExactly("response: INVALID_VALUE");
        assertThat(violations("{\"outcome\": \"MAGIC\"}")).containsExactly("contractVersion: REQUIRED", "outcome: INVALID_VALUE");
        assertThat(violations("{\"contractVersion\": \"assistant.v2\", \"outcome\": \"ANSWER\", \"answer\": \"a\"}"))
                .containsExactly("contractVersion: INVALID_VALUE");
        assertThat(violations("{\"contractVersion\": \"assistant.v1\", \"outcome\": \"REFUSED\", \"refusalReason\": \"BORED\"}"))
                .containsExactly("refusalReason: INVALID_VALUE");
    }
}
