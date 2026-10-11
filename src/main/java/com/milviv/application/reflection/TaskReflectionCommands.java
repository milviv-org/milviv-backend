package com.milviv.application.reflection;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.reflection.ReflectionAnswer;
import com.milviv.domain.reflection.ReflectionAnswerRepository;
import com.milviv.domain.reflection.ReflectionQuestion;
import com.milviv.domain.reflection.ReflectionQuestionRepository;
import com.milviv.domain.reflection.TaskReflectionRepository;
import com.milviv.domain.task.TaskCompletionRepository;
import com.milviv.domain.task.TaskRepository;

/**
 * Рефлексия по задаче (FR-12): выбор вопросов и ответы после выполнения.
 *
 * Правила:
 * - набор вопросов задачи — стандартные и свои; пустой набор отключает рефлексию;
 * - ответить можно только после выполнения, только на вопросы из набора задачи, по одному разу;
 * - ответ привязан к факту выполнения и не меняется (AR-04).
 */
public class TaskReflectionCommands {

    public static final int MAX_PAGE_SIZE = 100;

    private final TaskRepository tasks;
    private final TaskCompletionRepository completions;
    private final ReflectionQuestionRepository questions;
    private final TaskReflectionRepository taskQuestions;
    private final ReflectionAnswerRepository answers;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public TaskReflectionCommands(TaskRepository tasks, TaskCompletionRepository completions,
                                  ReflectionQuestionRepository questions, TaskReflectionRepository taskQuestions,
                                  ReflectionAnswerRepository answers, TransactionManager transactionManager, Clock clock) {
        this.tasks = tasks;
        this.completions = completions;
        this.questions = questions;
        this.taskQuestions = taskQuestions;
        this.answers = answers;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public record AnswerInput(UUID questionId, String answer) {}

    /**
     * @param questions вопросы задачи
     * @param answers   ответы на последнее выполнение; пусто — не выполнена или не ответили
     */
    public record TaskReflectionView(UUID taskId, List<ReflectionQuestionView> questions, List<ReflectionAnswerView> answers) {}

    public TaskReflectionView get(UUID userId, UUID taskId) {
        return transactionManager.execute(Propagation.SUPPORTS, () -> {
            requireTask(userId, taskId);
            return view(userId, taskId);
        });
    }

    public TaskReflectionView setQuestions(UUID userId, UUID taskId, List<UUID> questionIds) {
        Set<UUID> unique = new HashSet<>(questionIds);
        return transactionManager.execute(() -> {
            requireTask(userId, taskId);
            if (questions.countAvailable(userId, unique) != unique.size()) {
                throw new ValidationException("questionIds", "Вопрос не найден");
            }
            taskQuestions.replace(taskId, unique);
            return view(userId, taskId);
        });
    }

    public TaskReflectionView answer(UUID userId, UUID taskId, List<AnswerInput> input) {
        Instant now = clock.instant();
        return transactionManager.execute(() -> {
            requireTask(userId, taskId);
            long completionId = completions.findLatestIdByTaskForUpdate(userId, taskId)
                    .orElseThrow(() -> new ConflictException("Ответить можно после выполнения задачи"));

            Map<UUID, ReflectionQuestion> asked = taskQuestions.findQuestions(taskId).stream()
                    .collect(Collectors.toMap(ReflectionQuestion::getId, Function.identity()));
            Set<UUID> answered = answers.findByCompletion(userId, completionId).stream()
                    .map(ReflectionAnswer::questionId)
                    .collect(Collectors.toSet());

            Set<UUID> inRequest = new HashSet<>();
            for (AnswerInput item : input) {
                if (!asked.containsKey(item.questionId())) {
                    throw new ValidationException("answers", "Вопрос не выбран для этой задачи: " + item.questionId());
                }
                if (!inRequest.add(item.questionId())) {
                    throw new ValidationException("answers", "Вопрос повторяется: " + item.questionId());
                }
                if (answered.contains(item.questionId())) {
                    throw new ConflictException("На вопрос уже есть ответ: " + asked.get(item.questionId()).getText());
                }
            }
            for (AnswerInput item : input) {
                ReflectionQuestion question = asked.get(item.questionId());
                answers.add(new ReflectionAnswer(0, userId, completionId, taskId, question.getId(),
                        question.getText(), item.answer().trim(), now));
            }
            return view(userId, taskId);
        });
    }

    /** Все ответы пользователя, в том числе по удалённым задачам. */
    public List<ReflectionAnswerView> listAnswers(UUID userId, int limit, int offset) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return transactionManager.execute(Propagation.SUPPORTS, () -> answers.findByUser(userId, pageSize, from))
                .stream()
                .map(ReflectionAnswerView::from)
                .toList();
    }

    private TaskReflectionView view(UUID userId, UUID taskId) {
        List<ReflectionQuestionView> asked = taskQuestions.findQuestions(taskId).stream()
                .map(ReflectionQuestionView::from)
                .toList();
        List<ReflectionAnswerView> given = completions.findLatestIdByTask(userId, taskId)
                .map(completionId -> answers.findByCompletion(userId, completionId))
                .orElse(List.of())
                .stream()
                .map(ReflectionAnswerView::from)
                .toList();
        return new TaskReflectionView(taskId, asked, given);
    }

    private void requireTask(UUID userId, UUID taskId) {
        if (tasks.findById(userId, taskId).isEmpty()) {
            throw new NotFoundException("Задача не найдена");
        }
    }
}
