package com.milviv.unit.application.ai;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiConsentService;
import com.milviv.application.ai.AiDataCatalog;
import com.milviv.application.ai.AiLimits;
import com.milviv.application.ai.AiPlanPolicy;
import com.milviv.application.ai.AiTurnValidator;
import com.milviv.application.ai.ExecuteAiPlan;
import com.milviv.application.ai.HandleAiRequest;
import com.milviv.application.ai.PreviewBuilder;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.application.ai.contract.PlanDraft.Create;
import com.milviv.application.ai.contract.PlanDraft.GoalNode;
import com.milviv.application.ai.contract.PlanDraft.ProjectNode;
import com.milviv.application.ai.contract.PlanDraft.RewardNode;
import com.milviv.application.ai.contract.PlanDraft.TaskNode;
import com.milviv.application.auth.PasswordHasher;
import com.milviv.application.gamification.HiddenRewardsAccess;
import com.milviv.application.gamification.RewardCommands;
import com.milviv.application.goal.GoalCommands;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.ai.OperationReport.Item;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.AiRequestException.Code;
import com.milviv.domain.task.Difficulty;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.AiConsentJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.AiRequestJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.GoalJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.HiddenRewardsPasswordJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PlanJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ReflectionAnswerJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PointsHistoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ProjectJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.RewardJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskReflectionJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.infrastructure.security.InMemoryLoginAttemptLimiter;
import com.milviv.unit.application.ai.FakeAiAssistant.MutableClock;
import com.milviv.unit.infrastructure.persistence.H2TestUsers;

/** Создания по плану AI на H2: дерево со связями, отчёт, повтор без дубликатов, всё или ничего. */
class AiCreationsH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private Connection keepAlive;
    private TransactionManager tx;
    private TransactionalConnectionProvider connections;
    private AiConsentService consents;
    private final MutableClock clock = new MutableClock(NOW);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    /** Ручные сценарии на настоящих репозиториях — так же, как в Main. */
    record Commands(GoalCommands goals, ProjectCommands projects, TaskCommands tasks, RewardCommands rewards) {}

    static Commands commands(TransactionalConnectionProvider connections, TransactionManager tx, Clock clock) {
        var tasks = new TaskJdbcRepository(connections);
        var projects = new ProjectJdbcRepository(connections);
        var goals = new GoalJdbcRepository(connections);
        PasswordHasher hasher = new PasswordHasher() {
            @Override public String hash(String raw) { return "h:" + raw; }
            @Override public boolean matches(String raw, String hash) { return hash.equals("h:" + raw); }
        };
        var hiddenAccess = new HiddenRewardsAccess(new HiddenRewardsPasswordJdbcRepository(connections), hasher,
                new InMemoryLoginAttemptLimiter(5, Duration.ofMinutes(15), clock), tx);
        return new Commands(
                new GoalCommands(goals, tx, clock),
                new ProjectCommands(projects, tasks, goals, tx, clock),
                new TaskCommands(tasks, projects, goals, new TaskReflectionJdbcRepository(connections), tx, clock),
                new RewardCommands(new RewardJdbcRepository(connections), new PointsHistoryJdbcRepository(connections),
                        hiddenAccess, tx, clock));
    }

    static ExecuteAiPlan executeAiPlan(TransactionalConnectionProvider connections, TransactionManager tx, Clock clock) {
        Commands c = commands(connections, tx, clock);
        return new ExecuteAiPlan(c.goals(), c.projects(), c.tasks(), c.rewards(), tx);
    }

    static com.milviv.application.ai.AiUsageQuota usageQuota(TransactionalConnectionProvider connections, AiLimits limits) {
        return new com.milviv.application.ai.AiUsageQuota(new AiRequestJdbcRepository(connections, HandleAiRequestH2Test.MAPPER), limits);
    }

    static PreviewBuilder previewBuilder(TransactionalConnectionProvider connections, TransactionManager tx, Clock clock) {
        Commands c = commands(connections, tx, clock);
        return new PreviewBuilder(c.goals(), c.projects(), c.tasks(), c.rewards());
    }

    /** Каталог на настоящих репозиториях — так же, как в Main. */
    static AiDataCatalog catalog(TransactionalConnectionProvider connections, AiLimits limits, TransactionManager tx) {
        return new AiDataCatalog(new GoalJdbcRepository(connections), new ProjectJdbcRepository(connections),
                new TaskJdbcRepository(connections), new RewardJdbcRepository(connections),
                new PlanJdbcRepository(connections), new ReflectionAnswerJdbcRepository(connections), limits, tx);
    }

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:aicreate" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        H2TestUsers.createSchemaWithUsers(keepAlive, alice, bob);
        connections = new TransactionalConnectionProvider();
        tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
        consents = new AiConsentService(new AiConsentJdbcRepository(connections), tx, clock);
        consents.accept(alice);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private HandleAiRequest handler(FakeAiAssistant ai, AiLimits limits) {
        return new HandleAiRequest(consents, ai, new AiTurnValidator(limits, new AiPlanPolicy(limits)),
                executeAiPlan(connections, tx, clock), previewBuilder(connections, tx, clock), catalog(connections, limits, tx),
                new AiRequestJdbcRepository(connections, HandleAiRequestH2Test.MAPPER),
                limits, AiCreationsH2Test.usageQuota(connections, limits), new com.milviv.application.ai.ProviderQuotaBreaker(limits), tx, clock);
    }

    private HandleAiRequest handler(FakeAiAssistant ai) {
        return handler(ai, AiTurnValidatorTest.limits(4, Duration.ofSeconds(90)));
    }

    private static AiTurn.Proposal proposal(Create create) {
        return new AiTurn.Proposal("Создал цель и проекты", null, new PlanDraft(create));
    }

    private static TaskNode task(String title, Difficulty difficulty, Integer points) {
        return new TaskNode(title, null, difficulty, points, null, null);
    }

    private static Create englishGoal() {
        return new Create(
                new GoalNode(null, "Выучить английский", "B2",
                        List.of(new ProjectNode(null, "Грамматика", null, null, List.of(task("Времена Simple", Difficulty.EXPERT, null)))),
                        List.of(task("Найти репетитора", Difficulty.NOVICE, 7))),
                List.of(new ProjectNode(null, "Чтение", null, null, List.of())),
                List.of(task("Купить словарь", Difficulty.APPRENTICE, null)),
                List.of(new RewardNode("Кино", null, 500)));
    }

    @Test
    void creations_areExecutedAtOnce_withLinks_andReportFromDatabase() throws SQLException {
        HandleAiRequest.Result result = handler(new FakeAiAssistant().thenTurn(proposal(englishGoal())))
                .execute(alice, "k1", "Хочу выучить английский");

        assertThat(result.request().status()).isEqualTo(AiRequestStatus.EXECUTED);
        assertThat(result.request().summary()).isEqualTo("Создал цель и проекты");
        List<Item> report = result.request().report();
        assertThat(report).extracting(Item::itemId)
                .containsExactly("g1", "g1.p1", "g1.p1.t1", "g1.t1", "p1", "t1", "r1");
        assertThat(report).extracting(Item::type).containsExactly(EntityType.GOAL, EntityType.PROJECT, EntityType.TASK,
                EntityType.TASK, EntityType.PROJECT, EntityType.TASK, EntityType.REWARD);

        UUID goalId = report.get(0).id();
        UUID grammarId = report.get(1).id();
        assertThat(count("SELECT COUNT(*) FROM goals WHERE id = '" + goalId + "' AND user_id = '" + alice + "'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM projects WHERE id = '" + grammarId + "' AND goal_id = '" + goalId + "'")).isEqualTo(1);
        // задача проекта — в проекте; задача цели — в цели; баллы по сложности или свои
        assertThat(count("SELECT COUNT(*) FROM tasks WHERE id = '" + report.get(2).id() + "' AND project_id = '" + grammarId
                + "' AND points = 8")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tasks WHERE id = '" + report.get(3).id() + "' AND goal_id = '" + goalId
                + "' AND project_id IS NULL AND points = 7")).isEqualTo(1);
        // задачи AI получают стандартные вопросы рефлексии, как ручные (K-11)
        assertThat(count("SELECT COUNT(*) FROM task_reflection_questions WHERE task_id = '" + report.get(5).id() + "'")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM rewards WHERE id = '" + report.get(6).id() + "' AND hidden = FALSE")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tasks WHERE user_id = '" + bob + "'")).isZero();
    }

    @Test
    void repeatWithSameKey_returnsSameReport_withoutDuplicates() throws SQLException {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(proposal(englishGoal()));
        HandleAiRequest handler = handler(ai);

        HandleAiRequest.Result first = handler.execute(alice, "k1", "Хочу выучить английский");
        HandleAiRequest.Result repeat = handler.execute(alice, "k1", "Хочу выучить английский");

        assertThat(repeat.created()).isFalse();
        assertThat(repeat.request().report()).isEqualTo(first.request().report());
        assertThat(count("SELECT COUNT(*) FROM goals")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tasks")).isEqualTo(3);
        assertThat(ai.prompts()).hasSize(1);
    }

    @Test
    void invalidPlan_isRepaired_thenCreated() throws SQLException {
        FakeAiAssistant ai = new FakeAiAssistant()
                .thenTurn(proposal(new Create(null, null, List.of(task("Без сложности", null, null)), null)))
                .thenTurn(proposal(new Create(null, null, List.of(task("Со сложностью", Difficulty.ADEPT, null)), null)));

        HandleAiRequest.Result result = handler(ai).execute(alice, "k1", "Задача");

        assertThat(ai.prompts().get(1).violations()).containsExactly("plan.create.tasks[0].difficulty: REQUIRED");
        assertThat(result.request().report()).singleElement().extracting(Item::title).isEqualTo("Со сложностью");
        assertThat(count("SELECT COUNT(*) FROM tasks")).isEqualTo(1);
    }

    @Test
    void tooLargePlan_isRejected_withoutRepair_andNothingCreated() throws SQLException {
        FakeAiAssistant ai = new FakeAiAssistant()
                .thenTurn(proposal(new Create(null, null, Collections.nCopies(11, task("t", Difficulty.NOVICE, null)), null)));

        assertThatThrownBy(() -> handler(ai).execute(alice, "k1", "Создай 11 задач"))
                .satisfies(ex -> assertThat(((AiRequestException) ex).getCode()).isEqualTo(Code.AI_PLAN_TOO_LARGE));
        assertThat(ai.prompts()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM tasks")).isZero();
    }

    @Test
    void domainRuleViolatedAtExecution_rollsBackEverything() throws SQLException {
        // лимит стоимости для AI нарочно мягче доменного (1 000 000): правило домена срабатывает
        // на последнем узле, когда цель, проекты и задачи уже созданы в этой транзакции
        AiLimits base = AiTurnValidatorTest.limits(4, Duration.ofSeconds(90));
        AiLimits loose = new AiLimits(base.request(), base.data(), new AiLimits.Plan(40, 20, 10, 10, 10, 30, 3, 100, 1000,
                500, 2000, 300, 200, 2, 2_000_000));
        Create create = englishGoal();
        Create tooExpensive = new Create(create.goal(), create.projects(), create.tasks(),
                List.of(new RewardNode("Яхта", null, 1_500_000)));

        assertThatThrownBy(() -> handler(new FakeAiAssistant().thenTurn(proposal(tooExpensive)), loose)
                .execute(alice, "k1", "Хочу выучить английский"))
                .satisfies(ex -> assertThat(((AiRequestException) ex).getCode()).isEqualTo(Code.BUSINESS_RULE_VIOLATION));

        assertThat(count("SELECT COUNT(*) FROM goals")).isZero();
        assertThat(count("SELECT COUNT(*) FROM projects")).isZero();
        assertThat(count("SELECT COUNT(*) FROM tasks")).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_requests WHERE status = 'FAILED' AND error_code = 'BUSINESS_RULE_VIOLATION'"))
                .isEqualTo(1);
    }

    private int count(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ─── Каталог данных и existing (9.4) ──────────────────────────────────────

    private static String projectHandle(com.milviv.application.ai.AiAssistantPort.AiPrompt prompt) {
        return prompt.rounds().getLast().results().getFirst().items().stream()
                .map(item -> (com.milviv.application.ai.contract.DataRound.ProjectItem) item)
                .findFirst().orElseThrow().handle();
    }

    @Test
    void dataRequest_thenTasksIntoExistingProject() throws SQLException {
        var projects = new ProjectCommands(new ProjectJdbcRepository(connections), new TaskJdbcRepository(connections),
                new GoalJdbcRepository(connections), tx, clock);
        var existing = projects.create(alice, new com.milviv.domain.project.ProjectDetails(null, "Грамматика", null, null));

        FakeAiAssistant ai = new FakeAiAssistant()
                .thenTurn(new AiTurn.DataRequest("Найти проект по грамматике",
                        List.of(new com.milviv.application.ai.contract.DataQuery.ListProjects(null, null))))
                .thenTurn(prompt -> proposal(new Create(null,
                        List.of(new ProjectNode(projectHandle(prompt), null, null, null,
                                List.of(task("Present Perfect", Difficulty.ADEPT, null)))),
                        null, null)));

        HandleAiRequest.Result result = handler(ai).execute(alice, "k1", "Добавь задачу в грамматику");

        assertThat(result.request().status()).isEqualTo(AiRequestStatus.EXECUTED);
        // существующий проект не создаётся и не попадает в отчёт
        assertThat(result.request().report()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo("p1.t1");
            assertThat(item.type()).isEqualTo(EntityType.TASK);
        });
        assertThat(count("SELECT COUNT(*) FROM projects")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tasks WHERE project_id = '" + existing.id() + "'")).isEqualTo(1);
        assertThat(result.request().dataAccessed()).singleElement().satisfies(access -> {
            assertThat(access.query()).isEqualTo("LIST_PROJECTS");
            assertThat(access.items()).isEqualTo(1);
            assertThat(access.reason()).isEqualTo("Найти проект по грамматике");
        });
        // второй вызов получил данные первого раунда; ID в промпт не попадают
        assertThat(ai.prompts().get(1).rounds()).hasSize(1);
        assertThat(ai.prompts().get(1).rounds().toString()).doesNotContain(existing.id().toString());
        assertThat(count("SELECT COUNT(*) FROM ai_requests WHERE data_rounds = 1 AND handles IS NOT NULL"
                + " AND data_access_log IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void dataRequest_afterRoundsExhausted_isViolation() {
        var listGoals = new AiTurn.DataRequest("Цели", List.of(new com.milviv.application.ai.contract.DataQuery.ListGoals()));
        FakeAiAssistant ai = new FakeAiAssistant()
                .thenTurn(listGoals)
                .thenTurn(listGoals)
                .thenTurn(listGoals)
                .thenTurn(new AiTurn.Answer("Целей нет"));

        HandleAiRequest.Result result = handler(ai).execute(alice, "k1", "Какие у меня цели?");

        assertThat(result.request().status()).isEqualTo(AiRequestStatus.ANSWERED);
        assertThat(ai.prompts()).hasSize(4);
        assertThat(ai.prompts().get(3).violations()).containsExactly("outcome: DATA_ROUNDS_EXHAUSTED");
        assertThat(ai.prompts().get(3).rounds()).hasSize(2);
        assertThat(result.request().dataAccessed()).hasSize(2);
    }

    @Test
    void unknownExistingHandle_isRepaired() throws SQLException {
        FakeAiAssistant ai = new FakeAiAssistant()
                .thenTurn(proposal(new Create(null, List.of(new ProjectNode("p_fake", null, null, null,
                        List.of(task("t", Difficulty.NOVICE, null)))), null, null)))
                .thenTurn(proposal(new Create(null, null, List.of(task("Одиночная", Difficulty.NOVICE, null)), null)));

        handler(ai).execute(alice, "k1", "Добавь задачу");

        assertThat(ai.prompts().get(1).violations()).containsExactly("plan.create.projects[0].existing: UNKNOWN_HANDLE");
        assertThat(count("SELECT COUNT(*) FROM tasks")).isEqualTo(1);
    }
}
