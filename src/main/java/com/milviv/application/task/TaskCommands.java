package com.milviv.application.task;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.task.Task;
import com.milviv.domain.task.TaskDetails;
import com.milviv.domain.task.TaskRepository;
import com.milviv.domain.task.TaskStatus;

/**
 * Сценарии работы с задачей без начисления баллов: создать, прочитать, изменить, удалить, отменить.
 * Выполнение задачи — отдельный сценарий CompleteTask (он затрагивает баллы и историю).
 *
 * Чужая задача неотличима от несуществующей: 404 в обоих случаях.
 */
public class TaskCommands {

    public static final int MAX_PAGE_SIZE = 100;

    private final TaskRepository tasks;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public TaskCommands(TaskRepository tasks, TransactionManager transactionManager, Clock clock) {
        this.tasks = tasks;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public TaskView create(UUID userId, TaskDetails details) {
        Instant now = clock.instant();
        Task task = Task.create(userId, details, now);
        transactionManager.execute(() -> tasks.add(task));
        return view(task);
    }

    public TaskView get(UUID userId, UUID taskId) {
        return view(transactionManager.execute(Propagation.SUPPORTS, () -> load(userId, taskId)));
    }

    public List<TaskView> list(UUID userId, TaskStatus status, int limit, int offset) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        LocalDate today = today();
        return transactionManager.execute(Propagation.SUPPORTS, () -> tasks.findByUser(userId, status, pageSize, from))
                .stream()
                .map(task -> TaskView.from(task, today))
                .toList();
    }

    /**
     * @param expectedVersion версия, которую видел клиент; изменилась — 409,
     *                        чтобы не затереть чужие правки
     */
    public TaskView update(UUID userId, UUID taskId, TaskDetails details, long expectedVersion) {
        Instant now = clock.instant();
        return view(transactionManager.execute(() -> {
            Task task = load(userId, taskId);
            requireVersion(task, expectedVersion);
            task.edit(details, now);
            tasks.update(task);
            return task;
        }));
    }

    public TaskView cancel(UUID userId, UUID taskId) {
        Instant now = clock.instant();
        return view(transactionManager.execute(() -> {
            Task task = load(userId, taskId);
            task.cancel(now);
            tasks.update(task);
            return task;
        }));
    }

    /** История выполнения и начисленные баллы остаются (G-17, G-20). */
    public void delete(UUID userId, UUID taskId) {
        boolean deleted = transactionManager.execute(() -> tasks.delete(userId, taskId));
        if (!deleted) {
            throw notFound();
        }
    }

    private Task load(UUID userId, UUID taskId) {
        return tasks.findById(userId, taskId).orElseThrow(TaskCommands::notFound);
    }

    private static void requireVersion(Task task, long expectedVersion) {
        // ConflictException, а не OptimisticLockException: повтор транзакции тут не поможет —
        // клиент должен перечитать задачу
        if (task.getVersion() != expectedVersion) {
            throw new ConflictException("Задача была изменена. Обновите данные и повторите");
        }
    }

    static NotFoundException notFound() {
        return new NotFoundException("Задача не найдена");
    }

    private TaskView view(Task task) {
        return TaskView.from(task, today());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
