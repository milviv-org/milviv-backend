package com.milviv.application.reflection;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.reflection.ReflectionQuestion;
import com.milviv.domain.reflection.ReflectionQuestionRepository;

/**
 * Свои вопросы рефлексии. Стандартные видны всем и не меняются: для изменения и удаления
 * они неотличимы от несуществующих — 404, как и чужие.
 */
public class ReflectionQuestionCommands {

    private final ReflectionQuestionRepository questions;
    private final ReflectionPolicy policy;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public ReflectionQuestionCommands(ReflectionQuestionRepository questions, ReflectionPolicy policy,
                                      TransactionManager transactionManager, Clock clock) {
        this.questions = questions;
        this.policy = policy;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    /** Стандартные и свои. */
    public List<ReflectionQuestionView> list(UUID userId) {
        return transactionManager.execute(Propagation.SUPPORTS, () -> questions.findAvailable(userId))
                .stream()
                .map(ReflectionQuestionView::from)
                .toList();
    }

    public ReflectionQuestionView create(UUID userId, String text) {
        ReflectionQuestion question = ReflectionQuestion.create(userId, text, clock.instant());
        transactionManager.execute(() -> {
            // мягкий предел: два параллельных запроса могут превысить его на один вопрос
            if (questions.countOwn(userId) >= policy.maxOwnQuestions()) {
                throw new ConflictException("Можно создать не больше " + policy.maxOwnQuestions() + " своих вопросов");
            }
            questions.add(question);
        });
        return ReflectionQuestionView.from(question);
    }

    /** Ответы хранят текст на момент ответа — правка вопроса их не меняет. */
    public ReflectionQuestionView update(UUID userId, UUID questionId, String text) {
        return ReflectionQuestionView.from(transactionManager.execute(() -> {
            ReflectionQuestion question = questions.findOwn(userId, questionId).orElseThrow(ReflectionQuestionCommands::notFound);
            question.edit(text);
            questions.updateText(question);
            return question;
        }));
    }

    /** Вопрос пропадает из задач; ответы на него остаются. */
    public void delete(UUID userId, UUID questionId) {
        boolean deleted = transactionManager.execute(() -> questions.deleteOwn(userId, questionId));
        if (!deleted) {
            throw notFound();
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Вопрос не найден");
    }
}
