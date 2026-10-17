package com.milviv.unit.application.ai;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiAssistantPort.AiPrompt;
import com.milviv.application.ai.AiConsentService;
import com.milviv.application.ai.AiLimits;
import com.milviv.application.ai.AiPlanPolicy;
import com.milviv.application.ai.AiRequestView;
import com.milviv.application.ai.AiRequestView.PendingItem;
import com.milviv.application.ai.AiTurnValidator;
import com.milviv.application.ai.DecideAiProposal;
import com.milviv.application.ai.HandleAiRequest;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.DataQuery;
import com.milviv.application.ai.contract.DataRound;
import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.application.ai.contract.PlanDraft.Create;
import com.milviv.application.ai.contract.PlanDraft.Delete;
import com.milviv.application.ai.contract.PlanDraft.ProjectNode;
import com.milviv.application.ai.contract.PlanDraft.ProjectSet;
import com.milviv.application.ai.contract.PlanDraft.ProjectUpdate;
import com.milviv.application.ai.contract.PlanDraft.RewardSet;
import com.milviv.application.ai.contract.PlanDraft.RewardUpdate;
import com.milviv.application.ai.contract.PlanDraft.TaskNode;
import com.milviv.application.ai.contract.PlanDraft.TaskSet;
import com.milviv.application.ai.contract.PlanDraft.TaskUpdate;
import com.milviv.application.ai.contract.PlanDraft.Update;
import com.milviv.application.gamification.RewardView;
import com.milviv.application.project.ProjectView;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.FieldChange;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.ai.OperationReport.Item;
import com.milviv.domain.ai.OperationReport.Result;
import com.milviv.domain.ai.PendingOperation.Op;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.AiRequestException.Code;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.gamification.RewardDetails;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskDetails;
import com.milviv.domain.task.TaskStatus;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.AiConsentJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.AiRequestJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.unit.application.ai.AiCreationsH2Test.Commands;
import com.milviv.unit.application.ai.FakeAiAssistant.MutableClock;
import com.milviv.unit.infrastructure.persistence.H2TestUsers;

/** Изменения и удаления по плану AI на H2: карточка, согласие на каждую операцию, сверка версий, порядок K-9. */
class AiChangesH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-11");

    private Connection keepAlive;
    private TransactionManager tx;
    private TransactionalConnectionProvider connections;
    private AiConsentService consents;
    private Commands commands;
    private DecideAiProposal decide;
    private final MutableClock clock = new MutableClock(NOW);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private ProjectView grammar;
    private ProjectView old;
    private TaskView simple;
    private RewardView cinema;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:aichange" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        H2TestUsers.createSchemaWithUsers(keepAlive, alice, bob);
        connections = new TransactionalConnectionProvider();
        tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
        consents = new AiConsentService(new AiConsentJdbcRepository(connections), tx, clock);
        consents.accept(alice);
        commands = AiCreationsH2Test.commands(connections, tx, clock);
        decide = new DecideAiProposal(consents, AiCreationsH2Test.executeAiPlan(connections, tx, clock),
                new AiRequestJdbcRepository(connections, HandleAiRequestH2Test.MAPPER), tx, clock);

        grammar = commands.projects().create(alice, new ProjectDetails(null, "Грамматика", null, TODAY.plusDays(30)));
        old = commands.projects().create(alice, new ProjectDetails(null, "Старый", null, null));
        simple = commands.tasks().create(alice, new TaskDetails("Simple", null, Difficulty.NOVICE, null, null,
                TODAY.plusDays(10), NOW.plus(Duration.ofDays(1)), grammar.id(), null));
        cinema = commands.rewards().create(alice, new RewardDetails("Кино", null, 300, false), null);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    // ─── Помощники ────────────────────────────────────────────────────────────

    private HandleAiRequest handler(FakeAiAssistant ai) {
        AiLimits limits = AiTurnValidatorTest.limits(4, Duration.ofSeconds(90));
        return new HandleAiRequest(consents, ai, new AiTurnValidator(limits, new AiPlanPolicy(limits)),
                AiCreationsH2Test.executeAiPlan(connections, tx, clock), AiCreationsH2Test.previewBuilder(connections, tx, clock),
                AiCreationsH2Test.catalog(connections, limits, tx),
                new AiRequestJdbcRepository(connections, HandleAiRequestH2Test.MAPPER), limits, AiCreationsH2Test.usageQuota(connections, limits), new com.milviv.application.ai.ProviderQuotaBreaker(limits),
                new com.milviv.infrastructure.persistence.jdbc.AiMemoryJdbcRepository(connections), tx, clock);
    }

    /** AI читает проекты, задачи и награды, затем предлагает план по выданным дескрипторам. */
    private static FakeAiAssistant ai(Function<AiPrompt, PlanDraft> plan) {
        return new FakeAiAssistant()
                .thenTurn(new AiTurn.DataRequest("Посмотреть проекты, задачи и награды", List.of(
                        new DataQuery.ListProjects(null, null), new DataQuery.ListTasks(null, null, null, null, null),
                        new DataQuery.ListRewards())))
                .thenTurn(prompt -> new AiTurn.Proposal("Пояснение к изменениям", null, plan.apply(prompt)));
    }

    private AiRequestView propose(Function<AiPrompt, PlanDraft> plan) {
        return handler(ai(plan)).execute(alice, "k1", "Перенеси задачи и удали старое").request();
    }

    /** Дескриптор сущности с этим названием из данных, которые AI получил. */
    private static String handle(AiPrompt prompt, String title) {
        return prompt.rounds().stream()
                .flatMap(round -> round.results().stream())
                .flatMap(result -> result.items().stream())
                .map(item -> switch (item) {
                    case DataRound.ProjectItem p -> p.title().equals(title) ? p.handle() : null;
                    case DataRound.TaskItem t -> t.title().equals(title) ? t.handle() : null;
                    case DataRound.RewardItem r -> r.title().equals(title) ? r.handle() : null;
                    case DataRound.GoalItem g -> g.title().equals(title) ? g.handle() : null;
                    default -> null;
                })
                .filter(Objects::nonNull)
                .findFirst().orElseThrow();
    }

    private static <T> FieldChange<T> to(T value) {
        return new FieldChange<>(value);
    }

    private static TaskSet taskDeadline(LocalDate deadline) {
        return new TaskSet(null, null, null, null, null, to(deadline), null, null);
    }

    private static ProjectSet projectDeadline(LocalDate deadline) {
        return new ProjectSet(null, null, to(deadline), null);
    }

    /** Новая задача + перенос «Simple» + удаление «Старого». */
    private static PlanDraft standardPlan(AiPrompt prompt) {
        return new PlanDraft(
                new Create(null, null, List.of(new TaskNode("Новая", null, Difficulty.NOVICE, null, null, null)), null),
                new Update(List.of(new TaskUpdate(handle(prompt, "Simple"), taskDeadline(TODAY.plusDays(20)))), null, null, null),
                List.of(new Delete(handle(prompt, "Старый"))));
    }

    private int count(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void sql(String sql) {
        try (Statement st = keepAlive.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void assertCode(Throwable thrown, Code code) {
        assertThat(thrown).isInstanceOf(AiRequestException.class);
        assertThat(((AiRequestException) thrown).getCode()).isEqualTo(code);
    }

    // ─── Карточка и решение ───────────────────────────────────────────────────

    @Test
    void creationsRunAtOnce_changesWaitForApproval_onlyApprovedAreExecuted() throws SQLException {
        AiRequestView proposed = propose(AiChangesH2Test::standardPlan);

        assertThat(proposed.status()).isEqualTo(AiRequestStatus.PROPOSED);
        assertThat(proposed.report()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo("t1");
            assertThat(item.result()).isEqualTo(Result.CREATED);
        });
        // названия — из БД, без деталей изменения
        assertThat(proposed.pendingApproval()).containsExactly(
                new PendingItem("u1", Op.UPDATE, EntityType.TASK, "Simple"),
                new PendingItem("d1", Op.DELETE, EntityType.PROJECT, "Старый"));
        assertThat(proposed.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(commands.tasks().get(alice, simple.id()).deadline()).isEqualTo(TODAY.plusDays(10));

        AiRequestView executed = decide.execute(alice, proposed.requestId(), List.of("u1"));

        assertThat(executed.status()).isEqualTo(AiRequestStatus.EXECUTED);
        assertThat(executed.pendingApproval()).isEmpty();
        assertThat(executed.report()).extracting(Item::itemId, Item::result).containsExactly(
                org.assertj.core.groups.Tuple.tuple("t1", Result.CREATED),
                org.assertj.core.groups.Tuple.tuple("u1", Result.UPDATED),
                org.assertj.core.groups.Tuple.tuple("d1", Result.CANCELLED));
        assertThat(commands.tasks().get(alice, simple.id()).deadline()).isEqualTo(TODAY.plusDays(20));
        assertThat(commands.projects().get(alice, old.id()).title()).isEqualTo("Старый");
        assertThat(count("SELECT COUNT(*) FROM ai_requests WHERE decision IS NOT NULL AND plan IS NOT NULL")).isEqualTo(1);

        // повтор того же решения — тот же ответ; другое решение — конфликт
        assertThat(decide.execute(alice, proposed.requestId(), List.of("u1"))).isEqualTo(executed);
        assertCode(org.assertj.core.api.Assertions.catchThrowable(
                () -> decide.execute(alice, proposed.requestId(), List.of("u1", "d1"))), Code.REQUEST_STATE_CONFLICT);
        assertCode(org.assertj.core.api.Assertions.catchThrowable(
                () -> decide.reject(alice, proposed.requestId())), Code.REQUEST_STATE_CONFLICT);
    }

    @Test
    void rejection_changesNothing_butCreationsStay() {
        AiRequestView proposed = propose(AiChangesH2Test::standardPlan);

        AiRequestView rejected = decide.reject(alice, proposed.requestId());

        assertThat(rejected.status()).isEqualTo(AiRequestStatus.REJECTED);
        assertThat(rejected.report()).extracting(Item::result)
                .containsExactly(Result.CREATED, Result.CANCELLED, Result.CANCELLED);
        assertThat(commands.tasks().get(alice, simple.id()).deadline()).isEqualTo(TODAY.plusDays(10));
        assertThat(commands.projects().get(alice, old.id())).isNotNull();
        assertThat(commands.tasks().get(alice, proposed.report().getFirst().id()).title()).isEqualTo("Новая");
        // пустой набор — то же решение
        assertThat(decide.execute(alice, proposed.requestId(), List.of())).isEqualTo(rejected);
    }

    @Test
    void approval_mustReferToPendingItems_ofOwnRequest() {
        AiRequestView proposed = propose(AiChangesH2Test::standardPlan);
        UUID id = proposed.requestId();

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> decide.execute(alice, id, List.of("t1"))),
                Code.APPROVAL_INVALID);
        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> decide.execute(alice, id, List.of("u9"))),
                Code.APPROVAL_INVALID);
        assertThatThrownBy(() -> decide.execute(alice, id, null)).isInstanceOf(ValidationException.class);
        consents.accept(bob);
        assertThatThrownBy(() -> decide.execute(bob, id, List.of("u1"))).isInstanceOf(NotFoundException.class);
        assertThat(handler(new FakeAiAssistant()).get(alice, id).status()).isEqualTo(AiRequestStatus.PROPOSED);
    }

    // ─── Сверка версий и TTL ──────────────────────────────────────────────────

    @Test
    void targetChangedAfterProposal_nothingIsChanged_requestStaysProposed() {
        AiRequestView proposed = propose(AiChangesH2Test::standardPlan);
        TaskView current = commands.tasks().get(alice, simple.id());
        commands.tasks().update(alice, simple.id(), new TaskDetails("Simple!", null, Difficulty.NOVICE, null, null,
                current.deadline(), current.reminderAt(), grammar.id(), null), current.version());

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> decide.execute(alice, proposed.requestId(), List.of("u1", "d1")));

        assertCode(thrown, Code.TARGET_CHANGED);
        assertThat(thrown).hasMessageContaining("«Simple!»");
        assertThat(commands.projects().get(alice, old.id())).isNotNull();
        assertThat(handler(new FakeAiAssistant()).get(alice, proposed.requestId()).status()).isEqualTo(AiRequestStatus.PROPOSED);
    }

    @Test
    void rewardMadeHidden_isTargetChanged_withoutItsTitle() {
        AiRequestView proposed = propose(prompt -> new PlanDraft(null,
                new Update(null, null, null, List.of(new RewardUpdate(handle(prompt, "Кино"), new RewardSet(null, null, to(500))))),
                null));
        sql("UPDATE rewards SET hidden = TRUE, version = version + 1 WHERE id = '" + cinema.id() + "'");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> decide.execute(alice, proposed.requestId(), List.of("u1")));

        assertCode(thrown, Code.TARGET_CHANGED);
        assertThat(thrown.getMessage()).doesNotContain("Кино");
    }

    @Test
    void expiredProposal_cannotBeDecided() throws SQLException {
        AiRequestView proposed = propose(AiChangesH2Test::standardPlan);
        clock.advance(Duration.ofHours(25));

        AiRequestView shown = handler(new FakeAiAssistant()).get(alice, proposed.requestId());
        assertThat(shown.status()).isEqualTo(AiRequestStatus.EXPIRED);
        assertThat(shown.pendingApproval()).isEmpty();

        assertCode(org.assertj.core.api.Assertions.catchThrowable(
                () -> decide.execute(alice, proposed.requestId(), List.of("u1"))), Code.PLAN_EXPIRED);
        assertThat(count("SELECT COUNT(*) FROM ai_requests WHERE status = 'EXPIRED'")).isEqualTo(1);
        assertCode(org.assertj.core.api.Assertions.catchThrowable(
                () -> decide.reject(alice, proposed.requestId())), Code.PLAN_EXPIRED);
        assertThat(commands.tasks().get(alice, simple.id()).deadline()).isEqualTo(TODAY.plusDays(10));
    }

    @Test
    void targetChangedBeforeCard_failsRequest_andRollsBackCreations() throws SQLException {
        FakeAiAssistant ai = ai(AiChangesH2Test::standardPlan).duringCall(prompt -> {
            if (prompt.call() == 2) {
                // задачу изменили вручную, пока AI думал над планом
                sql("UPDATE tasks SET version = version + 1 WHERE id = '" + simple.id() + "'");
            }
        });

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> handler(ai).execute(alice, "k1", "Перенеси")),
                Code.TARGET_CHANGED);
        assertThat(count("SELECT COUNT(*) FROM tasks WHERE title = 'Новая'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_requests WHERE status = 'FAILED' AND error_code = 'TARGET_CHANGED'")).isEqualTo(1);
    }

    // ─── Порядок исполнения (K-9) ─────────────────────────────────────────────

    private AiRequestView proposeDates(TaskSet taskSet, boolean deleteTask, LocalDate projectDeadline) {
        return propose(prompt -> new PlanDraft(null,
                new Update(taskSet == null ? null : List.of(new TaskUpdate(handle(prompt, "Simple"), taskSet)),
                        List.of(new ProjectUpdate(handle(prompt, "Грамматика"), projectDeadline(projectDeadline))), null, null),
                deleteTask ? List.of(new Delete(handle(prompt, "Simple"))) : null));
    }

    @Test
    void growProject_thenMoveTaskLater() {
        AiRequestView proposed = proposeDates(taskDeadline(TODAY.plusDays(35)), false, TODAY.plusDays(40));

        assertThat(decide.execute(alice, proposed.requestId(), List.of("u1", "u2")).status()).isEqualTo(AiRequestStatus.EXECUTED);
        assertThat(commands.tasks().get(alice, simple.id()).deadline()).isEqualTo(TODAY.plusDays(35));
        assertThat(commands.projects().get(alice, grammar.id()).deadline()).isEqualTo(TODAY.plusDays(40));
    }

    @Test
    void deleteTask_thenShrinkProject() {
        AiRequestView proposed = proposeDates(null, true, TODAY.plusDays(5));

        assertThat(decide.execute(alice, proposed.requestId(), List.of("u1", "d1")).status()).isEqualTo(AiRequestStatus.EXECUTED);
        assertThat(commands.projects().get(alice, grammar.id()).deadline()).isEqualTo(TODAY.plusDays(5));
        assertThatThrownBy(() -> commands.tasks().get(alice, simple.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void moveTaskEarlier_thenShrinkProject_butNotShrinkAlone() {
        AiRequestView proposed = proposeDates(taskDeadline(TODAY.plusDays(3)), false, TODAY.plusDays(5));
        UUID id = proposed.requestId();

        // подтверждено не всё: итог нарушает правило — ничего не изменено
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> decide.execute(alice, id, List.of("u2")));
        assertCode(thrown, Code.BUSINESS_RULE_VIOLATION);
        assertThat(commands.projects().get(alice, grammar.id()).deadline()).isEqualTo(TODAY.plusDays(30));

        assertThat(decide.execute(alice, id, List.of("u1", "u2")).status()).isEqualTo(AiRequestStatus.EXECUTED);
        assertThat(commands.tasks().get(alice, simple.id()).deadline()).isEqualTo(TODAY.plusDays(3));
        assertThat(commands.projects().get(alice, grammar.id()).deadline()).isEqualTo(TODAY.plusDays(5));
    }

    // ─── Белый список и ref ───────────────────────────────────────────────────

    @Test
    void moveTaskIntoNewProject_byRef_keepsFieldsOutsideSet() {
        AiRequestView proposed = propose(prompt -> new PlanDraft(
                new Create(null, List.of(new ProjectNode("n_read", null, "Чтение", null, null, null)), null, null),
                new Update(List.of(new TaskUpdate(handle(prompt, "Simple"),
                        new TaskSet(to("Simple tenses"), null, null, null, null, null, to("n_read"), null))), null, null, null),
                null));
        UUID newProject = proposed.report().getFirst().id();

        decide.execute(alice, proposed.requestId(), List.of("u1"));

        TaskView task = commands.tasks().get(alice, simple.id());
        assertThat(task.projectId()).isEqualTo(newProject);
        assertThat(task.title()).isEqualTo("Simple tenses");
        assertThat(task.deadline()).isEqualTo(TODAY.plusDays(10));
        assertThat(task.reminderAt()).isEqualTo(simple.reminderAt());
        assertThat(task.status()).isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    void planWithOnlyChanges_isProposed_withEmptyReport() {
        AiRequestView proposed = propose(prompt -> new PlanDraft(null, null, List.of(new Delete(handle(prompt, "Старый")))));

        assertThat(proposed.status()).isEqualTo(AiRequestStatus.PROPOSED);
        assertThat(proposed.report()).isEmpty();

        AiRequestView executed = decide.execute(alice, proposed.requestId(), List.of("d1"));
        assertThat(executed.report()).singleElement().satisfies(item -> {
            assertThat(item.result()).isEqualTo(Result.DELETED);
            assertThat(item.title()).isEqualTo("Старый");
            assertThat(item.id()).isNull();
        });
        assertThatThrownBy(() -> commands.projects().get(alice, old.id())).isInstanceOf(NotFoundException.class);
    }
}
