package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.planning.CalendarPolicy;
import com.milviv.application.planning.CalendarQuery;
import com.milviv.application.planning.CalendarView;
import com.milviv.application.planning.PlanCommands;
import com.milviv.application.planning.PlanView;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.task.CompleteTask;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.planning.PlanDetails;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskDetails;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.GoalJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PlanJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PointsHistoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ProjectJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskCompletionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskReflectionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskRescheduleJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/** Планы и календарь на H2: CRUD, массив дней недели, история переносов, план против факта. */
class PlanningJdbcH2Test {

    // воскресенье
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-11");
    private static final LocalTime SEVEN_PM = LocalTime.of(19, 0);
    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);

    private Connection keepAlive;
    private PlanCommands plans;
    private TaskCommands tasks;
    private ProjectCommands projects;
    private CompleteTask completeTask;
    private CalendarQuery calendar;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:plans" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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

        plans = new PlanCommands(planRepository, new TaskRescheduleJdbcRepository(connections), taskRepository, tx, clock);
        tasks = new TaskCommands(taskRepository, projectRepository, goalRepository, new TaskReflectionJdbcRepository(connections), tx, clock);
        projects = new ProjectCommands(projectRepository, taskRepository, goalRepository, tx, clock);
        completeTask = new CompleteTask(taskRepository, new TaskCompletionJdbcRepository(connections),
                new PointsHistoryJdbcRepository(connections), planRepository, tx, clock);
        calendar = new CalendarQuery(planRepository, taskRepository, projectRepository, new CalendarPolicy(31), tx, clock);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private TaskView task(LocalDate deadline) {
        return tasks.create(alice, new TaskDetails("Задача", null, Difficulty.ADEPT, null, null, deadline, null, null, null));
    }

    private static PlanDetails dated(UUID taskId, LocalDate date) {
        return new PlanDetails(taskId, "Английский", null, date, null, SEVEN_PM, EIGHT_PM);
    }

    private static PlanDetails weekly(UUID taskId, Set<DayOfWeek> days, LocalTime from) {
        return new PlanDetails(taskId, "Повтор", null, null, days, from, from.plusHours(1));
    }

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    @Test
    void plan_crud_roundTripsDaysAndTimes_andIsolatesUsers() {
        TaskView task = task(null);
        PlanView weeklyPlan = plans.create(alice, weekly(task.id(), Set.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), SEVEN_PM));

        PlanView loaded = plans.get(alice, weeklyPlan.id());
        assertThat(loaded).isEqualTo(weeklyPlan);
        assertThat(loaded.daysOfWeek()).containsExactlyInAnyOrder(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
        assertThat(loaded.timeFrom()).isEqualTo(SEVEN_PM);
        assertThat(loaded.planDate()).isNull();

        PlanView changed = plans.update(alice, weeklyPlan.id(), dated(null, TODAY));
        assertThat(changed.daysOfWeek()).isNull();
        assertThat(plans.get(alice, weeklyPlan.id()).planDate()).isEqualTo(TODAY);

        assertThatThrownBy(() -> plans.get(bob, weeklyPlan.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> plans.update(bob, weeklyPlan.id(), dated(null, TODAY))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> plans.delete(bob, weeklyPlan.id())).isInstanceOf(NotFoundException.class);
        assertThat(plans.list(bob, null, 50, 0)).isEmpty();

        plans.delete(alice, weeklyPlan.id());
        assertThat(plans.list(alice, null, 50, 0)).isEmpty();
    }

    @Test
    void plan_shouldRejectOtherUsersTask() {
        TaskView bobsTask = tasks.create(bob, new TaskDetails("Боб", null, Difficulty.NOVICE, null, null, null, null, null, null));

        assertThatThrownBy(() -> plans.create(alice, dated(bobsTask.id(), TODAY)))
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrors().getFirst().field()).isEqualTo("taskId"));
    }

    @Test
    void list_shouldFilterByTask() {
        TaskView task = task(null);
        PlanView forTask = plans.create(alice, dated(task.id(), TODAY));
        plans.create(alice, dated(null, TODAY));

        assertThat(plans.list(alice, task.id(), 50, 0)).extracting(PlanView::id).containsExactly(forTask.id());
        assertThat(plans.list(alice, null, 50, 0)).hasSize(2);
    }

    @Test
    void deleteTask_shouldDeletePlans_butKeepReschedules() throws SQLException {
        TaskView task = task(null);
        PlanView plan = plans.create(alice, dated(task.id(), TODAY));
        plans.update(alice, plan.id(), dated(task.id(), TODAY.plusDays(1)));

        tasks.delete(alice, task.id());

        assertThat(plans.list(alice, null, 50, 0)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM task_reschedules WHERE task_id IS NULL")).isEqualTo(1);
    }

    // ─── История (QG-04) ──────────────────────────────────────────────────────

    @Test
    void movingTaskPlanDate_shouldRecordReschedule() throws SQLException {
        TaskView task = task(null);
        PlanView plan = plans.create(alice, dated(task.id(), TODAY));

        plans.update(alice, plan.id(), dated(task.id(), TODAY.plusDays(2)));
        plans.update(alice, plan.id(), dated(task.id(), TODAY.plusDays(2)));   // дата та же — не перенос
        plans.update(alice, plan.id(), dated(task.id(), TODAY.plusDays(5)));

        assertThat(count("SELECT COUNT(*) FROM task_reschedules WHERE task_id = '" + task.id() + "'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM task_reschedules WHERE from_date = DATE '2026-10-13' AND to_date = DATE '2026-10-16'"))
                .isEqualTo(1);
    }

    @Test
    void completion_shouldStoreLatestPlannedDate() throws SQLException {
        TaskView planned = task(null);
        plans.create(alice, dated(planned.id(), TODAY.minusDays(3)));
        plans.create(alice, dated(planned.id(), TODAY.minusDays(1)));
        plans.create(alice, weekly(planned.id(), Set.of(DayOfWeek.MONDAY), SEVEN_PM));
        TaskView unplanned = task(null);

        completeTask.execute(alice, planned.id());
        completeTask.execute(alice, unplanned.id());

        assertThat(count("SELECT COUNT(*) FROM task_completions WHERE task_id = '" + planned.id()
                + "' AND planned_date = DATE '2026-10-10'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM task_completions WHERE task_id = '" + unplanned.id()
                + "' AND planned_date IS NULL")).isEqualTo(1);
    }

    // ─── Календарь ────────────────────────────────────────────────────────────

    @Test
    void calendar_shouldPlaceEverythingOnItsDay() {
        LocalDate wednesday = LocalDate.parse("2026-10-14");
        LocalDate friday = LocalDate.parse("2026-10-16");
        TaskView dueWednesday = task(wednesday);
        TaskView done = task(null);
        completeTask.execute(alice, done.id());
        plans.create(alice, dated(null, wednesday));
        plans.create(alice, weekly(null, Set.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalTime.of(8, 0)));
        projects.create(alice, new ProjectDetails(null, "Проект", null, friday));
        // чужие данные в календарь не попадают
        plans.create(bob, dated(null, wednesday));

        CalendarView view = calendar.get(alice, TODAY, friday);

        assertThat(view.days()).hasSize(6).extracting(CalendarView.Day::date).startsWith(TODAY).endsWith(friday);
        CalendarView.Day today = view.days().get(0);
        CalendarView.Day wed = view.days().get(3);
        CalendarView.Day thu = view.days().get(4);
        CalendarView.Day fri = view.days().get(5);

        assertThat(today.completedTasks()).extracting(TaskView::id).containsExactly(done.id());
        // повторяющийся в 08:00 раньше разового в 19:00
        assertThat(wed.plans()).extracting(PlanView::title).containsExactly("Повтор", "Английский");
        assertThat(wed.taskDeadlines()).extracting(TaskView::id).containsExactly(dueWednesday.id());
        assertThat(thu.plans()).isEmpty();
        assertThat(fri.plans()).extracting(PlanView::title).containsExactly("Повтор");
        assertThat(fri.projectDeadlines()).hasSize(1);
    }

    @Test
    void calendar_shouldLimitPeriod() {
        assertThatThrownBy(() -> calendar.get(alice, TODAY, TODAY.minusDays(1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> calendar.get(alice, TODAY, TODAY.plusDays(31))).isInstanceOf(ValidationException.class);
        assertThat(calendar.get(alice, TODAY, TODAY.plusDays(30)).days()).hasSize(31);
    }

    private int count(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
