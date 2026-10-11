package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.reflection.ReflectionAnswerView;
import com.milviv.application.reflection.ReflectionPolicy;
import com.milviv.application.reflection.ReflectionQuestionCommands;
import com.milviv.application.reflection.ReflectionQuestionView;
import com.milviv.application.reflection.TaskReflectionCommands;
import com.milviv.application.reflection.TaskReflectionCommands.AnswerInput;
import com.milviv.application.reflection.TaskReflectionCommands.TaskReflectionView;
import com.milviv.application.task.CompleteTask;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskDetails;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.GoalJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PlanJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PointsHistoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ProjectJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ReflectionAnswerJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ReflectionQuestionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskCompletionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskReflectionJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/** Рефлексия на H2: вопросы, набор вопросов задачи, ответы после выполнения, сохранность ответов. */
class ReflectionJdbcH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID HELPED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HINDERED = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private Connection keepAlive;
    private ReflectionQuestionCommands questions;
    private TaskReflectionCommands reflection;
    private TaskCommands tasks;
    private CompleteTask completeTask;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:reflection" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        try (Statement st = keepAlive.createStatement()) {
            H2WorkSchema.create(st);
        }

        TransactionalConnectionProvider connections = new TransactionalConnectionProvider();
        TransactionManager tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TaskJdbcRepository taskRepository = new TaskJdbcRepository(connections);
        TaskCompletionJdbcRepository completions = new TaskCompletionJdbcRepository(connections);
        ReflectionQuestionJdbcRepository questionRepository = new ReflectionQuestionJdbcRepository(connections);
        TaskReflectionJdbcRepository taskQuestions = new TaskReflectionJdbcRepository(connections);

        questions = new ReflectionQuestionCommands(questionRepository, new ReflectionPolicy(2), tx, clock);
        reflection = new TaskReflectionCommands(taskRepository, completions, questionRepository, taskQuestions,
                new ReflectionAnswerJdbcRepository(connections), tx, clock);
        tasks = new TaskCommands(taskRepository, new ProjectJdbcRepository(connections), new GoalJdbcRepository(connections),
                taskQuestions, tx, clock);
        completeTask = new CompleteTask(taskRepository, completions, new PointsHistoryJdbcRepository(connections),
                new PlanJdbcRepository(connections), tx, clock);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private TaskView task(UUID user) {
        return tasks.create(user, new TaskDetails("Задача", null, Difficulty.NOVICE, null, null, null, null, null, null));
    }

    // ─── Вопросы ──────────────────────────────────────────────────────────────

    @Test
    void questions_standardFirst_ownIsolated_andLimited() {
        ReflectionQuestionView own = questions.create(alice, "  Сколько времени ушло?  ");
        assertThat(own.text()).isEqualTo("Сколько времени ушло?");
        assertThat(own.standard()).isFalse();

        assertThat(questions.list(alice)).hasSize(4).first().extracting(ReflectionQuestionView::standard).isEqualTo(true);
        assertThat(questions.list(bob)).hasSize(3).allMatch(ReflectionQuestionView::standard);

        questions.create(alice, "Второй");
        assertThatThrownBy(() -> questions.create(alice, "Третий")).isInstanceOf(ConflictException.class);
    }

    @Test
    void standardAndForeignQuestions_cannotBeChanged() {
        ReflectionQuestionView own = questions.create(alice, "Свой");

        assertThatThrownBy(() -> questions.update(alice, HELPED, "взлом")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> questions.delete(alice, HELPED)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> questions.update(bob, own.id(), "взлом")).isInstanceOf(NotFoundException.class);

        assertThat(questions.update(alice, own.id(), "Новый текст").text()).isEqualTo("Новый текст");
    }

    // ─── Набор вопросов задачи ────────────────────────────────────────────────

    @Test
    void newTask_getsStandardQuestions_andSetCanBeReplacedOrDisabled() {
        TaskView task = task(alice);
        assertThat(reflection.get(alice, task.id()).questions()).extracting(ReflectionQuestionView::standard)
                .containsExactly(true, true, true);

        ReflectionQuestionView own = questions.create(alice, "Свой");
        TaskReflectionView replaced = reflection.setQuestions(alice, task.id(), List.of(HINDERED, own.id()));
        assertThat(replaced.questions()).extracting(ReflectionQuestionView::id).containsExactly(HINDERED, own.id());

        assertThat(reflection.setQuestions(alice, task.id(), List.of()).questions()).isEmpty();
    }

    @Test
    void setQuestions_shouldRejectForeignQuestions_andForeignTask() {
        TaskView task = task(alice);
        ReflectionQuestionView bobs = questions.create(bob, "Вопрос Боба");

        assertThatThrownBy(() -> reflection.setQuestions(alice, task.id(), List.of(bobs.id())))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> reflection.setQuestions(bob, task.id(), List.of(HELPED)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> reflection.get(bob, task.id())).isInstanceOf(NotFoundException.class);
    }

    // ─── Ответы ───────────────────────────────────────────────────────────────

    @Test
    void answers_onlyAfterCompletion_forAskedQuestions_once() {
        TaskView task = task(alice);
        assertThatThrownBy(() -> reflection.answer(alice, task.id(), List.of(new AnswerInput(HELPED, "музыка"))))
                .isInstanceOf(ConflictException.class);

        completeTask.execute(alice, task.id());
        reflection.setQuestions(alice, task.id(), List.of(HELPED));

        assertThatThrownBy(() -> reflection.answer(alice, task.id(), List.of(new AnswerInput(HINDERED, "шум"))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> reflection.answer(alice, task.id(),
                List.of(new AnswerInput(HELPED, "a"), new AnswerInput(HELPED, "b"))))
                .isInstanceOf(ValidationException.class);

        TaskReflectionView answered = reflection.answer(alice, task.id(), List.of(new AnswerInput(HELPED, " музыка ")));
        assertThat(answered.answers()).singleElement().satisfies(answer -> {
            assertThat(answer.answer()).isEqualTo("музыка");
            assertThat(answer.questionText()).isEqualTo("Что помогло выполнить задачу?");
            assertThat(answer.taskId()).isEqualTo(task.id());
        });

        assertThatThrownBy(() -> reflection.answer(alice, task.id(), List.of(new AnswerInput(HELPED, "ещё"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> reflection.answer(bob, task.id(), List.of(new AnswerInput(HELPED, "взлом"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void answers_shouldSurviveQuestionEdit_questionDelete_andTaskDelete() {
        ReflectionQuestionView own = questions.create(alice, "Сколько времени ушло?");
        TaskView task = task(alice);
        reflection.setQuestions(alice, task.id(), List.of(own.id()));
        completeTask.execute(alice, task.id());
        reflection.answer(alice, task.id(), List.of(new AnswerInput(own.id(), "час")));

        questions.update(alice, own.id(), "Другой текст");
        assertThat(reflection.listAnswers(alice, 50, 0)).singleElement()
                .extracting(ReflectionAnswerView::questionText).isEqualTo("Сколько времени ушло?");

        questions.delete(alice, own.id());
        tasks.delete(alice, task.id());

        assertThat(reflection.listAnswers(alice, 50, 0)).singleElement().satisfies(answer -> {
            assertThat(answer.answer()).isEqualTo("час");
            assertThat(answer.questionId()).isNull();
            assertThat(answer.taskId()).isNull();
        });
        assertThat(reflection.listAnswers(bob, 50, 0)).isEmpty();
    }
}
