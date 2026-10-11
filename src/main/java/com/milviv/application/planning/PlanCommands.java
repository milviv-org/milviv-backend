package com.milviv.application.planning;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.planning.Plan;
import com.milviv.domain.planning.PlanDetails;
import com.milviv.domain.planning.PlanRepository;
import com.milviv.domain.planning.TaskRescheduleRepository;
import com.milviv.domain.task.TaskRepository;

/**
 * Сценарии работы с планом. Чужой план неотличим от несуществующего: 404.
 * Чужая задача в запросе — 400: «не найдена».
 *
 * Перенос даты разового плана задачи записывается в историю в той же транзакции (QG-04).
 */
public class PlanCommands {

    public static final int MAX_PAGE_SIZE = 100;

    private final PlanRepository plans;
    private final TaskRescheduleRepository reschedules;
    private final TaskRepository tasks;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public PlanCommands(PlanRepository plans, TaskRescheduleRepository reschedules, TaskRepository tasks,
                        TransactionManager transactionManager, Clock clock) {
        this.plans = plans;
        this.reschedules = reschedules;
        this.tasks = tasks;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public PlanView create(UUID userId, PlanDetails details) {
        Plan plan = Plan.create(userId, details, clock.instant());
        transactionManager.execute(() -> {
            requireTask(userId, details.taskId());
            plans.add(plan);
        });
        return PlanView.from(plan);
    }

    public PlanView get(UUID userId, UUID planId) {
        return PlanView.from(transactionManager.execute(Propagation.SUPPORTS,
                () -> plans.findById(userId, planId).orElseThrow(PlanCommands::notFound)));
    }

    public List<PlanView> list(UUID userId, UUID taskId, int limit, int offset) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return transactionManager.execute(Propagation.SUPPORTS, () -> plans.findByUser(userId, taskId, pageSize, from))
                .stream()
                .map(PlanView::from)
                .toList();
    }

    public PlanView update(UUID userId, UUID planId, PlanDetails details) {
        Instant now = clock.instant();
        return PlanView.from(transactionManager.execute(() -> {
            Plan plan = plans.findByIdForUpdate(userId, planId).orElseThrow(PlanCommands::notFound);
            requireTask(userId, details.taskId());
            plan.edit(details, now).ifPresent(reschedules::add);
            plans.update(plan);
            return plan;
        }));
    }

    public void delete(UUID userId, UUID planId) {
        boolean deleted = transactionManager.execute(() -> plans.delete(userId, planId));
        if (!deleted) {
            throw notFound();
        }
    }

    private void requireTask(UUID userId, UUID taskId) {
        if (taskId != null && tasks.findById(userId, taskId).isEmpty()) {
            throw new ValidationException("taskId", "Задача не найдена");
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("План не найден");
    }
}
