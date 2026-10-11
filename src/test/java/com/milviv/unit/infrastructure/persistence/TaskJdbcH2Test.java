package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.task.CompleteTask;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.domain.gamification.PointsHistoryRepository;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.Task;
import com.milviv.domain.task.TaskDetails;
import com.milviv.domain.task.TaskFilter;
import com.milviv.domain.task.TaskStatus;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.GoalJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PlanJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PointsHistoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ProjectJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskCompletionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/**
 * Задачи на H2: SQL репозиториев, изоляция по пользователю,
 * атомарность выполнения задачи. Таблицы — H2WorkSchema.
 */
class TaskJdbcH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private Connection keepAlive;
    private TransactionManager tx;
    private TaskJdbcRepository tasks;
    private TaskCompletionJdbcRepository completions;
    private PointsHistoryJdbcRepository points;
    private TaskCommands commands;
    private TransactionalConnectionProvider connections;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:tasks" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        try (Statement st = keepAlive.createStatement()) {
            H2WorkSchema.create(st);
        }

        connections = new TransactionalConnectionProvider();
        tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
        tasks = new TaskJdbcRepository(connections);
        completions = new TaskCompletionJdbcRepository(connections);
        points = new PointsHistoryJdbcRepository(connections);
        commands = new TaskCommands(tasks, new ProjectJdbcRepository(connections), new GoalJdbcRepository(connections), tx, clock);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private static TaskDetails details(String title) {
        return new TaskDetails(title, "описание", Difficulty.EXPERT, null,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-20"), NOW.plusSeconds(3600), null, null);
    }

    // ─── CRUD и изоляция ──────────────────────────────────────────────────────

    @Test
    void create_andGet_shouldRoundTripAllFields() {
        TaskView created = commands.create(alice, details("Задача"));

        TaskView loaded = commands.get(alice, created.id());
        assertThat(loaded).isEqualTo(created);
        assertThat(loaded.points()).isEqualTo(8);
        assertThat(loaded.deadline()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(loaded.reminderAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void otherUsersTask_shouldBeInvisibleAndUntouchable() {
        TaskView task = commands.create(alice, details("Задача Алисы"));

        assertThatThrownBy(() -> commands.get(bob, task.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> commands.update(bob, task.id(), details("взлом"), task.version()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> commands.delete(bob, task.id())).isInstanceOf(NotFoundException.class);
        assertThat(commands.list(bob, TaskFilter.all(), 50, 0)).isEmpty();
        assertThat(commands.get(alice, task.id()).title()).isEqualTo("Задача Алисы");
    }

    @Test
    void list_shouldFilterByStatus_andPaginate() {
        commands.create(alice, details("первая"));
        TaskView second = commands.create(alice, details("вторая"));
        commands.cancel(alice, second.id());

        assertThat(commands.list(alice, TaskFilter.all(), 50, 0)).hasSize(2);
        assertThat(commands.list(alice, new TaskFilter(TaskStatus.CANCELLED, null, null), 50, 0)).extracting(TaskView::title).containsExactly("вторая");
        assertThat(commands.list(alice, TaskFilter.all(), 1, 0)).hasSize(1);
        assertThat(commands.list(alice, TaskFilter.all(), 1, 1)).hasSize(1);
        assertThat(commands.list(alice, TaskFilter.all(), 50, 2)).isEmpty();
    }

    @Test
    void update_shouldRequireCurrentVersion() {
        TaskView task = commands.create(alice, details("старое"));

        TaskView updated = commands.update(alice, task.id(), details("новое"), task.version());
        assertThat(updated.title()).isEqualTo("новое");
        assertThat(updated.version()).isEqualTo(task.version() + 1);

        // клиент со старой версией не затирает чужие правки
        assertThatThrownBy(() -> commands.update(alice, task.id(), details("затираю"), task.version()))
                .isInstanceOf(ConflictException.class);
        assertThat(commands.get(alice, task.id()).title()).isEqualTo("новое");
    }

    @Test
    void repositoryUpdate_shouldThrowOptimisticLock_whenVersionStale() {
        TaskView created = commands.create(alice, details("задача"));
        Task first = tx.execute(() -> tasks.findById(alice, created.id())).orElseThrow();
        Task second = tx.execute(() -> tasks.findById(alice, created.id())).orElseThrow();

        tx.execute(() -> tasks.update(first));

        assertThatThrownBy(() -> tx.execute(() -> tasks.update(second))).isInstanceOf(OptimisticLockException.class);
    }

    // ─── Выполнение ───────────────────────────────────────────────────────────

    @Test
    void complete_shouldWriteStatusCompletionAndPoints_together() throws SQLException {
        TaskView task = commands.create(alice, details("Задача"));
        CompleteTask completeTask = new CompleteTask(tasks, completions, points, new PlanJdbcRepository(connections), tx, clock);

        CompleteTask.Result result = completeTask.execute(alice, task.id());

        assertThat(result.pointsAwarded()).isEqualTo(8);
        assertThat(result.task().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(count("SELECT COUNT(*) FROM task_completions WHERE task_id = '" + task.id() + "' AND points = 8")).isEqualTo(1);
        assertThat(count("SELECT SUM(amount) FROM points_history WHERE user_id = '" + alice + "' AND kind = 'TASK_COMPLETED'")).isEqualTo(8);
        assertThatThrownBy(() -> completeTask.execute(alice, task.id())).isInstanceOf(ConflictException.class);
        assertThat(count("SELECT COUNT(*) FROM points_history")).isEqualTo(1);
    }

    @Test
    void complete_shouldRollBackEverything_whenPointsFail() throws SQLException {
        TaskView task = commands.create(alice, details("Задача"));
        PointsHistoryRepository failing = (u, t, p, r, at) -> { throw new IllegalStateException("points down"); };
        CompleteTask completeTask = new CompleteTask(tasks, completions, failing, new PlanJdbcRepository(connections), tx, clock);

        assertThatThrownBy(() -> completeTask.execute(alice, task.id())).hasMessage("points down");

        assertThat(commands.get(alice, task.id()).status()).isEqualTo(TaskStatus.ACTIVE);
        assertThat(count("SELECT COUNT(*) FROM task_completions")).isZero();
    }

    @Test
    void delete_shouldKeepCompletionAndPoints() throws SQLException {
        TaskView task = commands.create(alice, details("Задача"));
        new CompleteTask(tasks, completions, points, new PlanJdbcRepository(connections), tx, clock).execute(alice, task.id());

        commands.delete(alice, task.id());

        assertThatThrownBy(() -> commands.get(alice, task.id())).isInstanceOf(NotFoundException.class);
        assertThat(count("SELECT COUNT(*) FROM task_completions WHERE task_id IS NULL")).isEqualTo(1);
        assertThat(count("SELECT SUM(amount) FROM points_history WHERE user_id = '" + alice + "'")).isEqualTo(8);
    }

    private int count(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
