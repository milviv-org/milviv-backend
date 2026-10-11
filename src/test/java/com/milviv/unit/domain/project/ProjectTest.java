package com.milviv.unit.domain.project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.project.Project;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.project.ProjectStatus;
import static com.milviv.domain.task.Difficulty.ADEPT;
import static com.milviv.domain.task.Difficulty.APPRENTICE;
import static com.milviv.domain.task.Difficulty.EXPERT;
import static com.milviv.domain.task.Difficulty.LEGENDARY;
import static com.milviv.domain.task.Difficulty.NOVICE;

class ProjectTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate OCT_20 = LocalDate.parse("2026-10-20");

    private static Project project(LocalDate deadline) {
        return Project.create(UUID.randomUUID(), new ProjectDetails(null, "  Английский  ", null, deadline), NOW);
    }

    @Test
    void create_shouldBeActive_withoutDifficulty_andTrimmedTitle() {
        Project project = project(OCT_20);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.getCalculatedDifficulty()).isNull();
        assertThat(project.getTitle()).isEqualTo("Английский");
    }

    // ─── Сроки ────────────────────────────────────────────────────────────────

    @Test
    void taskDeadline_shouldNotBeAfterProjectDeadline() {
        Project project = project(OCT_20);

        assertThatCode(() -> project.requireTaskDeadlineFits(OCT_20)).doesNotThrowAnyException();
        assertThatCode(() -> project.requireTaskDeadlineFits(null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> project.requireTaskDeadlineFits(OCT_20.plusDays(1)))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrors().getFirst().field()).isEqualTo("deadline"));
    }

    @Test
    void projectWithoutDeadline_shouldAcceptAnyTaskDeadline() {
        assertThatCode(() -> project(null).requireTaskDeadlineFits(LocalDate.MAX)).doesNotThrowAnyException();
    }

    @Test
    void edit_shouldRejectDeadlineEarlierThanTasks() {
        Project project = project(OCT_20);
        ProjectDetails earlier = new ProjectDetails(null, "Английский", null, OCT_20.minusDays(5));

        assertThatThrownBy(() -> project.edit(earlier, OCT_20.minusDays(1), NOW)).isInstanceOf(ValidationException.class);
        assertThat(project.getDeadline()).isEqualTo(OCT_20);

        project.edit(earlier, OCT_20.minusDays(5), NOW);
        assertThat(project.getDeadline()).isEqualTo(OCT_20.minusDays(5));
    }

    @Test
    void edit_shouldAllowRemovingDeadline_orWhenTasksHaveNone() {
        Project project = project(OCT_20);

        project.edit(new ProjectDetails(null, "x", null, OCT_20.minusYears(1)), null, NOW);
        project.edit(new ProjectDetails(null, "x", null, null), OCT_20, NOW);

        assertThat(project.getDeadline()).isNull();
    }

    // ─── Сложность ────────────────────────────────────────────────────────────

    @Test
    void calculateDifficulty_shouldRoundAverageLevel() {
        assertThat(Project.calculateDifficulty(List.of())).isNull();
        assertThat(Project.calculateDifficulty(List.of(EXPERT))).isEqualTo(EXPERT);
        // (0 + 5) / 2 = 2.5 → 3
        assertThat(Project.calculateDifficulty(List.of(NOVICE, LEGENDARY))).isEqualTo(EXPERT);
        // (0 + 0 + 2) / 3 = 0.67 → 1
        assertThat(Project.calculateDifficulty(List.of(NOVICE, NOVICE, ADEPT))).isEqualTo(APPRENTICE);
        // (0 + 0 + 0 + 5) / 4 = 1.25 → 1
        assertThat(Project.calculateDifficulty(List.of(NOVICE, NOVICE, NOVICE, LEGENDARY))).isEqualTo(APPRENTICE);
    }

    // ─── Статус ───────────────────────────────────────────────────────────────

    @Test
    void complete_andCancel_onlyFromActive() {
        Project completed = project(null);
        completed.complete(NOW);
        assertThat(completed.getStatus()).isEqualTo(ProjectStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> completed.cancel(NOW)).isInstanceOf(ConflictException.class);

        Project cancelled = project(null);
        cancelled.cancel(NOW);
        assertThat(cancelled.getCompletedAt()).isNull();
        assertThatThrownBy(() -> cancelled.complete(NOW)).isInstanceOf(ConflictException.class);
    }
}
