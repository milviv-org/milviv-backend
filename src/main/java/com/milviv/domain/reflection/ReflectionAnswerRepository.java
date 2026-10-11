package com.milviv.domain.reflection;

import java.util.List;
import java.util.UUID;

/** Ответы рефлексии. Только добавление. Каждый метод фильтрует по userId (AD-14). */
public interface ReflectionAnswerRepository {

    void add(ReflectionAnswer answer);

    List<ReflectionAnswer> findByCompletion(UUID userId, long completionId);

    /** Все ответы пользователя, новые первыми. */
    List<ReflectionAnswer> findByUser(UUID userId, int limit, int offset);
}
