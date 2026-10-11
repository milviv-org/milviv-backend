package com.milviv.domain.reflection;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Какие вопросы задать после выполнения задачи. Нет строк — рефлексия для задачи отключена.
 * Принадлежность задачи пользователю проверяет прикладной слой.
 */
public interface TaskReflectionRepository {

    List<ReflectionQuestion> findQuestions(UUID taskId);

    /** Заменить набор вопросов задачи. Пустой — отключить рефлексию. */
    void replace(UUID taskId, Collection<UUID> questionIds);

    /** Назначить все стандартные вопросы — набор по умолчанию для новой задачи. */
    void assignStandard(UUID taskId);
}
