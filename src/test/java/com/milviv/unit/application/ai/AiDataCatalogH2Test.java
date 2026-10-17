package com.milviv.unit.application.ai;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiDataCatalog;
import com.milviv.application.ai.AiLimits;
import com.milviv.application.ai.HandleRegistry;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.DataQuery;
import com.milviv.application.ai.contract.DataQuery.Period;
import com.milviv.application.ai.contract.DataRound;
import com.milviv.application.ai.contract.DataRound.DataResult;
import com.milviv.application.ai.contract.DataRound.RejectedRequest;
import com.milviv.application.goal.GoalCommands;
import com.milviv.application.goal.GoalView;
import com.milviv.application.planning.PlanCommands;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.project.ProjectView;
import com.milviv.application.reflection.TaskReflectionCommands;
import com.milviv.application.task.CompleteTask;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.gamification.Reward;
import com.milviv.domain.gamification.RewardDetails;
import com.milviv.domain.goal.GoalDetails;
import com.milviv.domain.planning.PlanDetails;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskDetails;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.GoalJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PlanJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PointsHistoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ProjectJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ReflectionAnswerJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ReflectionQuestionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.RewardJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskCompletionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskReflectionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskRescheduleJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.unit.application.ai.FakeAiAssistant.MutableClock;
import com.milviv.unit.infrastructure.persistence.H2TestUsers;

/** Каталог данных для AI на H2: проекции, дескрипторы, изоляция, скрытые награды, урезание, отклонения. */
class AiDataCatalogH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-11");
    private static final UUID HELPED = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Connection keepAlive;
    private TransactionManager tx;
    private TransactionalConnectionProvider connections;
    private final MutableClock clock = new MutableClock(NOW);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private GoalView english;
    private ProjectView grammar;
    private TaskView simple;
    private TaskView loose;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:aicatalog" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        H2TestUsers.createSchemaWithUsers(keepAlive, alice, bob);
        connections = new TransactionalConnectionProvider();
        tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);

        var taskRepo = new TaskJdbcRepository(connections);
        var projectRepo = new ProjectJdbcRepository(connections);
        var goalRepo = new GoalJdbcRepository(connections);
        var planRepo = new PlanJdbcRepository(connections);
        var completions = new TaskCompletionJdbcRepository(connections);
        var taskQuestions = new TaskReflectionJdbcRepository(connections);
        var goals = new GoalCommands(goalRepo, tx, clock);
        var projects = new ProjectCommands(projectRepo, taskRepo, goalRepo, tx, clock);
        var tasks = new TaskCommands(taskRepo, projectRepo, goalRepo, taskQuestions, tx, clock);
        var plans = new PlanCommands(planRepo, new TaskRescheduleJdbcRepository(connections), taskRepo, tx, clock);
        var complete = new CompleteTask(taskRepo, completions, new PointsHistoryJdbcRepository(connections), planRepo, tx, clock);
        var reflection = new TaskReflectionCommands(taskRepo, completions, new ReflectionQuestionJdbcRepository(connections),
                taskQuestions, new ReflectionAnswerJdbcRepository(connections), tx, clock);

        english = goals.create(alice, new GoalDetails("Английский", "Уровень B2"));
        grammar = projects.create(alice, new ProjectDetails(english.id(), "Грамматика", null, TODAY.plusDays(30)));
        simple = tasks.create(alice, new TaskDetails("Времена Simple", "x".repeat(50), Difficulty.EXPERT, null, null,
                TODAY.plusDays(5), null, grammar.id(), null));
        loose = tasks.create(alice, new TaskDetails("Купить словарь", null, Difficulty.NOVICE, null, null, null, null, null, null));
        complete.execute(alice, loose.id());
        reflection.answer(alice, loose.id(), List.of(new TaskReflectionCommands.AnswerInput(HELPED, "Список покупок")));
        plans.create(alice, new PlanDetails(simple.id(), "Учить времена", null, TODAY.plusDays(1), null,
                LocalTime.of(19, 0), LocalTime.of(20, 0)));

        var rewards = new RewardJdbcRepository(connections);
        tx.execute(() -> {
            rewards.add(Reward.create(alice, new RewardDetails("Кино", null, 500, false), NOW));
            // скрытая награда того же пользователя — для AI её нет (G-5)
            rewards.add(Reward.create(alice, new RewardDetails("Тайное", null, 100, true), NOW));
        });

        // у второго пользователя похожие данные — их не должно быть видно
        goals.create(bob, new GoalDetails("Английский", "Чужая цель"));
        tasks.create(bob, new TaskDetails("Времена Simple", null, Difficulty.EXPERT, null, null, null, null, null, null));
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private static AiLimits limits(int maxItems, int textMaxLength) {
        AiLimits base = AiTurnValidatorTest.limits(4, Duration.ofSeconds(90));
        return new AiLimits(base.request(), new AiLimits.Data(2, 3, maxItems, textMaxLength, 16_000), base.plan(), base.memory());
    }

    private DataRound run(AiLimits limits, HandleRegistry registry, AiDataCatalog.Budget budget, DataQuery... queries) {
        AiDataCatalog catalog = AiCreationsH2Test.catalog(connections, limits, tx);
        return catalog.execute(alice, 1, new AiTurn.DataRequest("Нужно для ответа", List.of(queries)), registry, budget);
    }

    private DataRound run(HandleRegistry registry, DataQuery... queries) {
        return run(limits(100, 1000), registry, new AiDataCatalog.Budget(16_000), queries);
    }

    private static DataResult only(DataRound round) {
        assertThat(round.rejected()).isEmpty();
        assertThat(round.results()).hasSize(1);
        return round.results().getFirst();
    }

    @Test
    void goalsAndProjects_areProjected_withHandlesOfOwnDataOnly() {
        HandleRegistry registry = new HandleRegistry();

        DataResult goals = only(run(registry, new DataQuery.ListGoals()));
        assertThat(goals.items()).singleElement().isInstanceOfSatisfying(DataRound.GoalItem.class, goal -> {
            assertThat(goal.title()).isEqualTo("Английский");
            assertThat(goal.description()).isEqualTo("Уровень B2");
            assertThat(goal.handle()).matches("g_[A-Za-z0-9]{4}");
            assertThat(registry.resolve(goal.handle(), EntityType.GOAL)).get()
                    .satisfies(h -> assertThat(h.id()).isEqualTo(english.id()));
        });
        String goalHandle = ((DataRound.GoalItem) goals.items().getFirst()).handle();

        DataResult projects = only(run(registry, new DataQuery.ListProjects(goalHandle, null)));
        assertThat(projects.items()).singleElement().isInstanceOfSatisfying(DataRound.ProjectItem.class, project -> {
            assertThat(project.title()).isEqualTo("Грамматика");
            assertThat(project.goal()).isEqualTo(goalHandle);
            assertThat(registry.resolve(project.handle(), EntityType.PROJECT)).get()
                    .satisfies(h -> assertThat(h.deadline()).isEqualTo(TODAY.plusDays(30)));
        });
    }

    @Test
    void tasks_filters_projection_andTextCut() {
        HandleRegistry registry = new HandleRegistry();
        DataRound round = run(limits(100, 10), registry,
                new AiDataCatalog.Budget(16_000),
                new DataQuery.ListTasks(null, null, true, null, null),
                new DataQuery.ListTasks(null, null, null, null, new Period(TODAY.plusDays(4), TODAY.plusDays(6))));

        assertThat(round.results().get(0).items()).singleElement()
                .isInstanceOfSatisfying(DataRound.TaskItem.class, t -> assertThat(t.title()).isEqualTo("Купить словарь"));
        assertThat(round.results().get(1).items()).singleElement().isInstanceOfSatisfying(DataRound.TaskItem.class, t -> {
            assertThat(t.title()).isEqualTo("Времена Simple");
            assertThat(t.description()).hasSize(10);
            // связанный проект получает дескриптор, даже если его не запрашивали
            assertThat(registry.resolve(t.project(), EntityType.PROJECT)).isPresent();
            assertThat(t.points()).isEqualTo(8);
        });
    }

    @Test
    void rejected_requests_doNotStopTheRound() {
        HandleRegistry registry = new HandleRegistry();
        String goalHandle = registry.issue(EntityType.GOAL, english.id(), 0, null);

        DataRound round = run(registry,
                new DataQuery.ListTasks("p_none", null, null, null, null),
                new DataQuery.ListTasks(goalHandle, null, null, null, null),
                new DataQuery.ListTasks(null, goalHandle, true, null, null));

        assertThat(round.results()).isEmpty();
        assertThat(round.rejected()).extracting(RejectedRequest::code)
                .containsExactly("UNKNOWN_HANDLE", "UNKNOWN_HANDLE", "CONFLICTING_PARAMS");

        DataRound mixed = run(registry, new DataQuery.ListPlans(null), new DataQuery.ListGoals());
        assertThat(mixed.rejected()).extracting(RejectedRequest::code).containsExactly("PERIOD_REQUIRED");
        assertThat(mixed.results()).extracting(DataResult::query).containsExactly("LIST_GOALS");
    }

    @Test
    void hiddenRewards_areInvisible_evenForTruncation() {
        // одна видимая и одна скрытая; лимит — 1 элемент: если бы скрытая учитывалась, был бы truncated
        DataResult rewards = only(run(limits(1, 1000), new HandleRegistry(), new AiDataCatalog.Budget(16_000),
                new DataQuery.ListRewards()));

        assertThat(rewards.truncated()).isFalse();
        assertThat(rewards.items()).singleElement()
                .isInstanceOfSatisfying(DataRound.RewardItem.class, r -> assertThat(r.title()).isEqualTo("Кино"));
        assertThat(rewards.toString()).doesNotContain("Тайное");
    }

    @Test
    void maxItems_andTokenBudget_truncate() {
        DataResult goalsAndMore = only(run(limits(1, 1000), new HandleRegistry(), new AiDataCatalog.Budget(16_000),
                new DataQuery.ListTasks(null, null, null, null, null)));
        assertThat(goalsAndMore.truncated()).isTrue();
        assertThat(goalsAndMore.items()).hasSize(1);

        DataResult noBudget = only(run(limits(100, 1000), new HandleRegistry(), new AiDataCatalog.Budget(1),
                new DataQuery.ListGoals()));
        assertThat(noBudget.truncated()).isTrue();
        assertThat(noBudget.items()).isEmpty();
    }

    @Test
    void plans_requireShortPeriod_andLinkTasks() {
        HandleRegistry registry = new HandleRegistry();
        DataRound tooLong = run(registry, new DataQuery.ListPlans(new Period(TODAY, TODAY.plusDays(31))));
        assertThat(tooLong.rejected()).extracting(RejectedRequest::code).containsExactly("PERIOD_TOO_LONG");

        DataResult plans = only(run(registry, new DataQuery.ListPlans(new Period(TODAY, TODAY.plusDays(30)))));
        assertThat(plans.items()).singleElement().isInstanceOfSatisfying(DataRound.PlanItem.class, plan -> {
            assertThat(plan.title()).isEqualTo("Учить времена");
            assertThat(registry.resolve(plan.task(), EntityType.TASK)).get()
                    .satisfies(h -> assertThat(h.id()).isEqualTo(simple.id()));
        });
    }

    @Test
    void reflections_areReturned_withTaskHandle() {
        HandleRegistry registry = new HandleRegistry();
        DataResult answers = only(run(registry, new DataQuery.ListReflections(null, new Period(TODAY, TODAY))));

        assertThat(answers.items()).singleElement().isInstanceOfSatisfying(DataRound.ReflectionItem.class, answer -> {
            assertThat(answer.question()).isEqualTo("Что помогло выполнить задачу?");
            assertThat(answer.answer()).isEqualTo("Список покупок");
            assertThat(registry.resolve(answer.task(), EntityType.TASK)).get()
                    .satisfies(h -> assertThat(h.id()).isEqualTo(loose.id()));
        });
    }
}
