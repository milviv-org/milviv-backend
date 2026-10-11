package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.goal.GoalCommands;
import com.milviv.application.goal.GoalView;
import com.milviv.application.planning.PlanCommands;
import com.milviv.application.planning.PlanView;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.project.ProjectView;
import com.milviv.application.statistics.ProgressView;
import com.milviv.application.statistics.StatisticsPolicy;
import com.milviv.application.statistics.StatisticsQueries;
import com.milviv.application.task.CompleteTask;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.goal.GoalDetails;
import com.milviv.domain.planning.PlanDetails;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.statistics.CompletionSummary;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskDetails;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.GoalJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PlanJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PointsHistoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ProjectJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.StatisticsJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskCompletionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskReflectionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskRescheduleJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/** Статистика на H2: сводка по истории (переживает удаление задач), прогресс проектов и целей. */
class StatisticsJdbcH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-11");

    private Connection keepAlive;
    private TaskCommands tasks;
    private ProjectCommands projects;
    private GoalCommands goals;
    private PlanCommands plans;
    private CompleteTask completeTask;
    private StatisticsQueries statistics;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stats" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        try (Statement st = keepAlive.createStatement()) {
            H2WorkSchema.create(st);
        }

        TransactionalConnectionProvider connections = new TransactionalConnectionProvider();
        TransactionManager tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TaskJdbcRepository taskRepository = new TaskJdbcRepository(connections);
        ProjectJdbcRepository projectRepository = new ProjectJdbcRepository(connections);
        GoalJdbcRepository goalRepository = new GoalJdbcRepository(connections);
        PlanJdbcRepository planRepository = new PlanJdbcRepository(connections);
        TaskCompletionJdbcRepository completions = new TaskCompletionJdbcRepository(connections);
        TaskRescheduleJdbcRepository reschedules = new TaskRescheduleJdbcRepository(connections);

        tasks = new TaskCommands(taskRepository, projectRepository, goalRepository,
                new TaskReflectionJdbcRepository(connections), tx, clock);
        projects = new ProjectCommands(projectRepository, taskRepository, goalRepository, tx, clock);
        goals = new GoalCommands(goalRepository, tx, clock);
        plans = new PlanCommands(planRepository, reschedules, taskRepository, tx, clock);
        completeTask = new CompleteTask(taskRepository, completions, new PointsHistoryJdbcRepository(connections),
                planRepository, tx, clock);
        statistics = new StatisticsQueries(completions, reschedules, new StatisticsJdbcRepository(connections),
                new StatisticsPolicy(31), tx, clock);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private TaskView task(UUID user, Difficulty difficulty, LocalDate deadline, UUID projectId, UUID goalId) {
        return tasks.create(user, new TaskDetails("Задача", null, difficulty, null, null, deadline, null, projectId, goalId));
    }

    @Test
    void summary_shouldUseHistory_andSurviveTaskDeletion() {
        TaskView late = task(alice, Difficulty.EXPERT, TODAY.minusDays(1), null, null);
        TaskView planned = task(alice, Difficulty.NOVICE, null, null, null);
        PlanView plan = plans.create(alice, new PlanDetails(planned.id(), "План", null, TODAY.minusDays(2), null,
                LocalTime.of(9, 0), LocalTime.of(10, 0)));
        plans.update(alice, plan.id(), new PlanDetails(planned.id(), "План", null, TODAY, null,
                LocalTime.of(9, 0), LocalTime.of(10, 0)));
        completeTask.execute(alice, late.id());
        completeTask.execute(alice, planned.id());
        task(bob, Difficulty.LEGENDARY, null, null, null);

        tasks.delete(alice, late.id());

        CompletionSummary summary = statistics.summary(alice, TODAY.minusDays(6), TODAY);
        assertThat(summary.total()).isEqualTo(new CompletionSummary.Bucket(2, 10));
        assertThat(summary.byDifficulty().get(Difficulty.EXPERT).completed()).isEqualTo(1);
        assertThat(summary.deadlines()).isEqualTo(new CompletionSummary.DeadlineStats(0, 1, 1));
        assertThat(summary.plan()).isEqualTo(new CompletionSummary.PlanStats(1, 0, 0, 1));
        assertThat(summary.reschedules()).isEqualTo(new CompletionSummary.RescheduleStats(1, 1));
        assertThat(summary.byDay()).hasSize(7).last().extracting(CompletionSummary.DayBucket::completed).isEqualTo(2);

        assertThat(statistics.summary(bob, TODAY.minusDays(6), TODAY).total().completed()).isZero();
    }

    @Test
    void summary_shouldLimitPeriod() {
        assertThatThrownBy(() -> statistics.summary(alice, TODAY, TODAY.minusDays(1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> statistics.summary(alice, TODAY, TODAY.plusDays(31))).isInstanceOf(ValidationException.class);
    }

    @Test
    void projectProgress_shouldCountTaskStates() {
        ProjectView project = projects.create(alice, new ProjectDetails(null, "Проект", null, null));
        ProjectView empty = projects.create(alice, new ProjectDetails(null, "Пустой", null, null));
        TaskView done = task(alice, Difficulty.EXPERT, null, project.id(), null);
        TaskView cancelled = task(alice, Difficulty.NOVICE, null, project.id(), null);
        task(alice, Difficulty.NOVICE, TODAY.minusDays(1), project.id(), null);
        task(alice, Difficulty.NOVICE, TODAY.plusDays(1), project.id(), null);
        completeTask.execute(alice, done.id());
        tasks.cancel(alice, cancelled.id());

        // оба проекта созданы в одну секунду фиксированных часов — порядок между ними не проверяем
        var rows = statistics.projects(alice, null, 50, 0).stream()
                .collect(java.util.stream.Collectors.toMap(StatisticsQueries.ProjectProgressView::projectId,
                        StatisticsQueries.ProjectProgressView::progress));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(project.id())).isEqualTo(new ProgressView(4, 2, 1, 1, 1, 8, 33));
        assertThat(rows.get(empty.id())).isEqualTo(new ProgressView(0, 0, 0, 0, 0, 0, null));
        assertThat(statistics.projects(bob, null, 50, 0)).isEmpty();
    }

    @Test
    void goalProgress_shouldIncludeDirectAndProjectTasks_once() {
        GoalView goal = goals.create(alice, new GoalDetails("Цель", null));
        ProjectView project = projects.create(alice, new ProjectDetails(goal.id(), "Проект цели", null, null));
        TaskView direct = task(alice, Difficulty.NOVICE, null, null, goal.id());
        task(alice, Difficulty.NOVICE, null, project.id(), null);
        // и в цели, и в её проекте — считается один раз
        task(alice, Difficulty.NOVICE, null, project.id(), goal.id());
        task(alice, Difficulty.NOVICE, null, null, null);
        completeTask.execute(alice, direct.id());

        var rows = statistics.goals(alice, 50, 0);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.projects()).isEqualTo(1);
            assertThat(row.progress().total()).isEqualTo(3);
            assertThat(row.progress().completed()).isEqualTo(1);
            assertThat(row.progress().pointsEarned()).isEqualTo(2);
            assertThat(row.progress().progressPercent()).isEqualTo(33);
        });
    }
}
