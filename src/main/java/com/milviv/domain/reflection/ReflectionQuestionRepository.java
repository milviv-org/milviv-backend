package com.milviv.domain.reflection;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Вопросы рефлексии. «Доступные» пользователю — стандартные и его собственные;
 * чужие вопросы не видны (AD-14).
 */
public interface ReflectionQuestionRepository {

    void add(ReflectionQuestion question);

    /** Стандартные первыми, затем свои по времени создания. */
    List<ReflectionQuestion> findAvailable(UUID userId);

    /** Только свой вопрос — стандартный и чужой не находятся. */
    Optional<ReflectionQuestion> findOwn(UUID userId, UUID questionId);

    /** Сколько из ids доступно пользователю. */
    int countAvailable(UUID userId, Collection<UUID> questionIds);

    int countOwn(UUID userId);

    void updateText(ReflectionQuestion question);

    /** Связи с задачами удаляются, ответы остаются (question_id → NULL). */
    boolean deleteOwn(UUID userId, UUID questionId);
}
