package com.milviv.unit.domain.planning;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.planning.Plan;
import com.milviv.domain.planning.PlanDetails;
import com.milviv.domain.planning.TaskReschedule;

class PlanTest {

    // воскресенье
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final LocalDate OCT_23 = LocalDate.parse("2026-10-23");
    private static final LocalTime SEVEN_PM = LocalTime.of(19, 0);
    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);
    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    private PlanDetails dated(UUID task, LocalDate date) {
        return new PlanDetails(task, " Английский ", null, date, null, SEVEN_PM, EIGHT_PM);
    }

    private PlanDetails weekly(Set<DayOfWeek> days) {
        return new PlanDetails(taskId, "Английский", null, null, days, SEVEN_PM, EIGHT_PM);
    }

    @Test
    void create_shouldRequireExactlyOneOfDateOrDays() {
        assertThatThrownBy(() -> Plan.create(userId, new PlanDetails(null, "x", null, null, null, SEVEN_PM, EIGHT_PM), NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Plan.create(userId, new PlanDetails(null, "x", null, null, Set.of(), SEVEN_PM, EIGHT_PM), NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Plan.create(userId,
                new PlanDetails(null, "x", null, OCT_23, Set.of(DayOfWeek.MONDAY), SEVEN_PM, EIGHT_PM), NOW))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_shouldRequireEndAfterStart() {
        assertThatThrownBy(() -> Plan.create(userId, new PlanDetails(null, "x", null, OCT_23, null, EIGHT_PM, SEVEN_PM), NOW))
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrors().getFirst().field()).isEqualTo("timeTo"));
        assertThatThrownBy(() -> Plan.create(userId, new PlanDetails(null, "x", null, OCT_23, null, SEVEN_PM, SEVEN_PM), NOW))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void datedPlan_occursOnlyOnItsDate() {
        Plan plan = Plan.create(userId, dated(null, OCT_23), NOW);

        assertThat(plan.isRecurring()).isFalse();
        assertThat(plan.getTitle()).isEqualTo("Английский");
        assertThat(plan.occursOn(OCT_23)).isTrue();
        assertThat(plan.occursOn(OCT_23.plusDays(7))).isFalse();
    }

    @Test
    void weeklyPlan_occursOnItsDays_fromCreationDate() {
        Plan plan = Plan.create(userId, weekly(Set.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), NOW);

        assertThat(plan.isRecurring()).isTrue();
        assertThat(plan.occursOn(LocalDate.parse("2026-10-14"))).isTrue();   // среда
        assertThat(plan.occursOn(LocalDate.parse("2026-10-16"))).isTrue();   // пятница
        assertThat(plan.occursOn(LocalDate.parse("2026-10-15"))).isFalse();  // четверг
        assertThat(plan.occursOn(LocalDate.parse("2026-10-09"))).isFalse();  // пятница до создания
    }

    @Test
    void edit_shouldReportReschedule_whenSameTaskDateChanges() {
        Plan plan = Plan.create(userId, dated(taskId, OCT_23), NOW);

        assertThat(plan.edit(dated(taskId, OCT_23.plusDays(2)), NOW))
                .contains(new TaskReschedule(userId, taskId, OCT_23, OCT_23.plusDays(2), NOW));
    }

    @Test
    void edit_shouldNotReportReschedule_otherwise() {
        Plan plan = Plan.create(userId, dated(taskId, OCT_23), NOW);
        // та же дата
        assertThat(plan.edit(dated(taskId, OCT_23), NOW)).isEmpty();
        // другая задача
        assertThat(plan.edit(dated(UUID.randomUUID(), OCT_23.plusDays(1)), NOW)).isEmpty();
        // разовый → повторяющийся
        assertThat(plan.edit(weekly(Set.of(DayOfWeek.MONDAY)), NOW)).isEmpty();

        Plan noTask = Plan.create(userId, dated(null, OCT_23), NOW);
        assertThat(noTask.edit(dated(null, OCT_23.plusDays(1)), NOW)).isEmpty();
    }
}
