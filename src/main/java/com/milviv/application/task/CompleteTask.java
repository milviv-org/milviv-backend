package com.milviv.application.task;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.gamification.PointsHistoryRepository;
import com.milviv.domain.task.Task;
import com.milviv.domain.task.TaskCompletion;
import com.milviv.domain.task.TaskCompletionRepository;
import com.milviv.domain.task.TaskRepository;

/**
 * Выполнение задачи → начисление баллов (раздел 6.1).
 *
 * Три записи в одной транзакции: статус задачи, факт выполнения, начисление.
 * Либо все, либо ни одной (QG-03). Параллельное изменение задачи —
 * OptimisticLockException, транзакция повторяется целиком.
 */
public class CompleteTask {

    private final TaskRepository tasks;
    private final TaskCompletionRepository completions;
    private final PointsHistoryRepository pointsHistory;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public CompleteTask(TaskRepository tasks, TaskCompletionRepository completions,
                        PointsHistoryRepository pointsHistory, TransactionManager transactionManager, Clock clock) {
        this.tasks = tasks;
        this.completions = completions;
        this.pointsHistory = pointsHistory;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public record Result(TaskView task, int pointsAwarded) {}

    public Result execute(UUID userId, UUID taskId) {
        Task completed = transactionManager.execute(() -> {
            Instant now = clock.instant();
            Task task = tasks.findById(userId, taskId).orElseThrow(TaskCommands::notFound);

            task.complete(now);
            tasks.update(task);
            // Планирование появится на этапе 4 — пока дата плана неизвестна
            completions.add(TaskCompletion.of(task, null));
            pointsHistory.recordTaskCompleted(userId, task.getId(), task.getPoints(),
                    "Task completed: " + task.getTitle(), now);
            return task;
        });

        return new Result(TaskView.from(completed, LocalDate.now(clock)), completed.getPoints());
    }
}
