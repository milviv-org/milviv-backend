package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
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

import com.milviv.application.goal.GoalCommands;
import com.milviv.application.goal.GoalView;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.project.ProjectView;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.goal.GoalDetails;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskDetails;
import com.milviv.domain.task.TaskFilter;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.GoalJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ProjectJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskReflectionJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/** Цели и проекты на H2: CRUD, изоляция, связи с задачами, правило сроков, сложность проекта. */
class GoalProjectJdbcH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate OCT_20 = LocalDate.parse("2026-10-20");

    private Connection keepAlive;
    private GoalCommands goals;
    private ProjectCommands projects;
    private TaskCommands tasks;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:work" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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

        goals = new GoalCommands(goalRepository, tx, clock);
        projects = new ProjectCommands(projectRepository, taskRepository, goalRepository, tx, clock);
        tasks = new TaskCommands(taskRepository, projectRepository, goalRepository, new TaskReflectionJdbcRepository(connections), tx, clock);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private ProjectView project(UUID userId, LocalDate deadline, UUID goalId) {
        return projects.create(userId, new ProjectDetails(goalId, "Проект", null, deadline));
    }

    private static TaskDetails task(Difficulty difficulty, LocalDate deadline, UUID projectId, UUID goalId) {
        return new TaskDetails("Задача", null, difficulty, null, null, deadline, null, projectId, goalId);
    }

    private static String errorField(Throwable ex) {
        return ((ValidationException) ex).getErrors().getFirst().field();
    }

    // ─── Цели ─────────────────────────────────────────────────────────────────

    @Test
    void goal_crud_andIsolation() {
        GoalView goal = goals.create(alice, new GoalDetails("  Выучить английский ", "B2"));
        assertThat(goals.get(alice, goal.id())).isEqualTo(goal);
        assertThat(goal.title()).isEqualTo("Выучить английский");

        GoalView updated = goals.update(alice, goal.id(), new GoalDetails("C1", null), goal.version());
        assertThat(updated.version()).isEqualTo(goal.version() + 1);
        assertThatThrownBy(() -> goals.update(alice, goal.id(), new GoalDetails("x", null), goal.version()))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> goals.get(bob, goal.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> goals.delete(bob, goal.id())).isInstanceOf(NotFoundException.class);
        assertThat(goals.list(bob, 50, 0)).isEmpty();
        assertThat(goals.list(alice, 50, 0)).extracting(GoalView::title).containsExactly("C1");
    }

    @Test
    void deleteGoal_shouldUnlinkProjectsAndTasks() {
        GoalView goal = goals.create(alice, new GoalDetails("Цель", null));
        ProjectView project = project(alice, null, goal.id());
        TaskView task = tasks.create(alice, task(Difficulty.NOVICE, null, null, goal.id()));

        goals.delete(alice, goal.id());

        assertThat(projects.get(alice, project.id()).goalId()).isNull();
        assertThat(tasks.get(alice, task.id()).goalId()).isNull();
    }

    // ─── Проекты ──────────────────────────────────────────────────────────────

    @Test
    void project_crud_filters_andIsolation() {
        GoalView goal = goals.create(alice, new GoalDetails("Цель", null));
        ProjectView inGoal = project(alice, OCT_20, goal.id());
        ProjectView other = project(alice, null, null);
        projects.cancel(alice, other.id());

        assertThat(projects.list(alice, null, goal.id(), 50, 0)).extracting(ProjectView::id).containsExactly(inGoal.id());
        assertThat(projects.list(alice, ProjectStatus.CANCELLED, null, 50, 0)).extracting(ProjectView::id).containsExactly(other.id());
        assertThat(projects.list(bob, null, null, 50, 0)).isEmpty();
        assertThatThrownBy(() -> projects.get(bob, inGoal.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> projects.complete(bob, inGoal.id())).isInstanceOf(NotFoundException.class);

        ProjectView completed = projects.complete(alice, inGoal.id());
        assertThat(completed.status()).isEqualTo(ProjectStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> projects.update(alice, inGoal.id(), new ProjectDetails(null, "x", null, null), inGoal.version()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void project_shouldRejectOtherUsersGoal() {
        GoalView bobsGoal = goals.create(bob, new GoalDetails("Цель Боба", null));

        assertThatThrownBy(() -> project(alice, null, bobsGoal.id()))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(errorField(ex)).isEqualTo("goalId"));
    }

    @Test
    void deleteProject_shouldUnlinkTasks() {
        ProjectView project = project(alice, null, null);
        TaskView task = tasks.create(alice, task(Difficulty.NOVICE, null, project.id(), null));

        projects.delete(alice, project.id());

        assertThat(tasks.get(alice, task.id()).projectId()).isNull();
        assertThatThrownBy(() -> projects.get(alice, project.id())).isInstanceOf(NotFoundException.class);
    }

    // ─── Связи задач ──────────────────────────────────────────────────────────

    @Test
    void task_shouldRejectOtherUsersProjectAndGoal() {
        ProjectView bobsProject = project(bob, null, null);
        GoalView bobsGoal = goals.create(bob, new GoalDetails("Цель Боба", null));

        assertThatThrownBy(() -> tasks.create(alice, task(Difficulty.NOVICE, null, bobsProject.id(), null)))
                .satisfies(ex -> assertThat(errorField(ex)).isEqualTo("projectId"));
        assertThatThrownBy(() -> tasks.create(alice, task(Difficulty.NOVICE, null, null, bobsGoal.id())))
                .satisfies(ex -> assertThat(errorField(ex)).isEqualTo("goalId"));
    }

    @Test
    void taskList_shouldFilterByProjectAndGoal() {
        GoalView goal = goals.create(alice, new GoalDetails("Цель", null));
        ProjectView project = project(alice, null, null);
        TaskView inProject = tasks.create(alice, task(Difficulty.NOVICE, null, project.id(), null));
        TaskView inGoal = tasks.create(alice, task(Difficulty.NOVICE, null, null, goal.id()));
        tasks.create(alice, task(Difficulty.NOVICE, null, null, null));

        assertThat(tasks.list(alice, new TaskFilter(null, project.id(), null), 50, 0)).extracting(TaskView::id).containsExactly(inProject.id());
        assertThat(tasks.list(alice, new TaskFilter(null, null, goal.id()), 50, 0)).extracting(TaskView::id).containsExactly(inGoal.id());
    }

    // ─── Сроки ────────────────────────────────────────────────────────────────

    @Test
    void taskDeadline_shouldNotExceedProjectDeadline() {
        ProjectView project = project(alice, OCT_20, null);

        assertThatThrownBy(() -> tasks.create(alice, task(Difficulty.NOVICE, OCT_20.plusDays(1), project.id(), null)))
                .satisfies(ex -> assertThat(errorField(ex)).isEqualTo("deadline"));

        TaskView fits = tasks.create(alice, task(Difficulty.NOVICE, OCT_20, project.id(), null));
        TaskView free = tasks.create(alice, task(Difficulty.NOVICE, OCT_20.plusDays(10), null, null));

        // перенос задачи в проект проверяет её срок
        assertThatThrownBy(() -> tasks.update(alice, free.id(), task(Difficulty.NOVICE, OCT_20.plusDays(10), project.id(), null), free.version()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> tasks.update(alice, fits.id(), task(Difficulty.NOVICE, OCT_20.plusDays(1), project.id(), null), fits.version()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void projectDeadline_shouldNotBeEarlierThanTaskDeadlines() {
        ProjectView project = project(alice, OCT_20, null);
        tasks.create(alice, task(Difficulty.NOVICE, OCT_20.minusDays(3), project.id(), null));

        assertThatThrownBy(() -> projects.update(alice, project.id(),
                new ProjectDetails(null, "Проект", null, OCT_20.minusDays(4)), project.version()))
                .satisfies(ex -> assertThat(errorField(ex)).isEqualTo("deadline"));

        ProjectView moved = projects.update(alice, project.id(),
                new ProjectDetails(null, "Проект", null, OCT_20.minusDays(3)), project.version());
        assertThat(moved.deadline()).isEqualTo(OCT_20.minusDays(3));
    }

    // ─── Сложность проекта ────────────────────────────────────────────────────

    @Test
    void projectDifficulty_shouldFollowTaskChanges() {
        ProjectView project = project(alice, null, null);
        assertThat(project.calculatedDifficulty()).isNull();

        TaskView novice = tasks.create(alice, task(Difficulty.NOVICE, null, project.id(), null));
        assertThat(difficulty(project)).isEqualTo(Difficulty.NOVICE);

        TaskView legendary = tasks.create(alice, task(Difficulty.LEGENDARY, null, project.id(), null));
        assertThat(difficulty(project)).isEqualTo(Difficulty.EXPERT);

        // отменённая задача не учитывается
        tasks.cancel(alice, novice.id());
        assertThat(difficulty(project)).isEqualTo(Difficulty.LEGENDARY);

        // задачу вынесли из проекта — сложность пересчитана у старого и нового проекта
        ProjectView second = project(alice, null, null);
        tasks.update(alice, legendary.id(), task(Difficulty.LEGENDARY, null, second.id(), null), legendary.version());
        assertThat(difficulty(project)).isNull();
        assertThat(difficulty(second)).isEqualTo(Difficulty.LEGENDARY);

        tasks.delete(alice, legendary.id());
        assertThat(difficulty(second)).isNull();
    }

    @Test
    void projectDifficultyChange_shouldNotInvalidateProjectVersion() {
        ProjectView project = project(alice, null, null);
        tasks.create(alice, task(Difficulty.ADEPT, null, project.id(), null));

        // форма проекта, открытая до изменения задач, сохраняется без 409
        ProjectView updated = projects.update(alice, project.id(),
                new ProjectDetails(null, "Новое имя", null, null), project.version());
        assertThat(updated.calculatedDifficulty()).isEqualTo(Difficulty.ADEPT);
    }

    private Difficulty difficulty(ProjectView project) {
        return projects.get(alice, project.id()).calculatedDifficulty();
    }
}
