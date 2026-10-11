package com.milviv.unit.domain.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.Task;
import com.milviv.domain.task.TaskDetails;
import com.milviv.domain.task.TaskStatus;

class TaskTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-11");
    private final UUID userId = UUID.randomUUID();

    private static TaskDetails details(Difficulty difficulty, Integer points, LocalDate start, LocalDate deadline) {
        return new TaskDetails("  Выучить времена  ", null, difficulty, points, start, deadline, null, null, null);
    }

    @Test
    void create_shouldUseDefaultPointsByDifficulty() {
        assertThat(Task.create(userId, details(Difficulty.NOVICE, null, null, null), NOW).getPoints()).isEqualTo(2);
        assertThat(Task.create(userId, details(Difficulty.LEGENDARY, null, null, null), NOW).getPoints()).isEqualTo(20);
    }

    @Test
    void create_shouldKeepCustomPoints_andTrimTitle() {
        Task task = Task.create(userId, details(Difficulty.ADEPT, 1000, null, null), NOW);

        assertThat(task.getPoints()).isEqualTo(1000);
        assertThat(task.getTitle()).isEqualTo("Выучить времена");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    void create_shouldRejectPointsOutOfRange() {
        assertThatThrownBy(() -> Task.create(userId, details(Difficulty.ADEPT, 0, null, null), NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Task.create(userId, details(Difficulty.ADEPT, 1001, null, null), NOW))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_shouldAllowDatesInPast_butNotStartAfterDeadline() {
        Task past = Task.create(userId, details(Difficulty.ADEPT, null, TODAY.minusDays(10), TODAY.minusDays(5)), NOW);
        assertThat(past.getDeadline()).isEqualTo(TODAY.minusDays(5));

        assertThatThrownBy(() -> Task.create(userId, details(Difficulty.ADEPT, null, TODAY, TODAY.minusDays(1)), NOW))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void complete_shouldMoveToCompleted_onlyFromActive() {
        Task task = Task.create(userId, details(Difficulty.ADEPT, null, null, null), NOW);

        task.complete(NOW);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> task.complete(NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> task.cancel(NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void cancel_shouldMoveToCancelled_andForbidCompletion() {
        Task task = Task.create(userId, details(Difficulty.ADEPT, null, null, null), NOW);

        task.cancel(NOW);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThatThrownBy(() -> task.complete(NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void completedTask_canBeEdited() {
        Task task = Task.create(userId, details(Difficulty.ADEPT, null, null, null), NOW);
        task.complete(NOW);

        task.edit(details(Difficulty.MASTER, null, null, null), NOW);

        assertThat(task.getPoints()).isEqualTo(10);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void isOverdue_onlyForActiveTaskWithPastDeadline() {
        Task overdue = Task.create(userId, details(Difficulty.ADEPT, null, null, TODAY.minusDays(1)), NOW);
        Task dueToday = Task.create(userId, details(Difficulty.ADEPT, null, null, TODAY), NOW);
        Task noDeadline = Task.create(userId, details(Difficulty.ADEPT, null, null, null), NOW);

        assertThat(overdue.isOverdue(TODAY)).isTrue();
        assertThat(dueToday.isOverdue(TODAY)).isFalse();
        assertThat(noDeadline.isOverdue(TODAY)).isFalse();

        overdue.complete(NOW);
        assertThat(overdue.isOverdue(TODAY)).isFalse();
    }
}
