package com.milviv.application.goal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.goal.Goal;
import com.milviv.domain.goal.GoalDetails;
import com.milviv.domain.goal.GoalRepository;

/** Сценарии работы с целью. Чужая цель неотличима от несуществующей: 404. */
public class GoalCommands {

    public static final int MAX_PAGE_SIZE = 100;

    private final GoalRepository goals;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public GoalCommands(GoalRepository goals, TransactionManager transactionManager, Clock clock) {
        this.goals = goals;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public GoalView create(UUID userId, GoalDetails details) {
        Goal goal = Goal.create(userId, details, clock.instant());
        transactionManager.execute(() -> goals.add(goal));
        return GoalView.from(goal);
    }

    public GoalView get(UUID userId, UUID goalId) {
        return GoalView.from(transactionManager.execute(Propagation.SUPPORTS, () -> load(userId, goalId)));
    }

    public List<GoalView> list(UUID userId, int limit, int offset) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return transactionManager.execute(Propagation.SUPPORTS, () -> goals.findByUser(userId, pageSize, from))
                .stream()
                .map(GoalView::from)
                .toList();
    }

    /** @param expectedVersion версия, которую видел клиент; изменилась — 409 */
    public GoalView update(UUID userId, UUID goalId, GoalDetails details, long expectedVersion) {
        Instant now = clock.instant();
        return GoalView.from(transactionManager.execute(() -> {
            Goal goal = load(userId, goalId);
            if (goal.getVersion() != expectedVersion) {
                throw new ConflictException("Цель была изменена. Обновите данные и повторите");
            }
            goal.edit(details, now);
            goals.update(goal);
            return goal;
        }));
    }

    /** Проекты и задачи цели остаются без цели (G-15). */
    public void delete(UUID userId, UUID goalId) {
        boolean deleted = transactionManager.execute(() -> goals.delete(userId, goalId));
        if (!deleted) {
            throw notFound();
        }
    }

    private Goal load(UUID userId, UUID goalId) {
        return goals.findById(userId, goalId).orElseThrow(GoalCommands::notFound);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Цель не найдена");
    }
}
