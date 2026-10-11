package com.milviv.domain.reflection;

import java.time.Instant;
import java.util.UUID;

/**
 * Ответ на вопрос рефлексии (AR-04). Привязан к факту выполнения, а не к задаче, —
 * остаётся после её удаления. Текст вопроса — копия на момент ответа. Не меняется.
 *
 * @param id         0 до сохранения
 * @param taskId     задача; null — удалена (берётся из факта выполнения)
 * @param questionId вопрос; null — удалён
 */
public record ReflectionAnswer(
        long id,
        UUID userId,
        long completionId,
        UUID taskId,
        UUID questionId,
        String questionText,
        String answer,
        Instant createdAt
) {}
