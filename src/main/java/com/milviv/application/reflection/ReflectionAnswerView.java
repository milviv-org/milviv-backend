package com.milviv.application.reflection;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.reflection.ReflectionAnswer;

/** Ответ для клиента. taskId, questionId = null — задача или вопрос удалены. */
public record ReflectionAnswerView(
        long id,
        UUID taskId,
        long completionId,
        UUID questionId,
        String questionText,
        String answer,
        Instant createdAt
) {

    public static ReflectionAnswerView from(ReflectionAnswer answer) {
        return new ReflectionAnswerView(answer.id(), answer.taskId(), answer.completionId(), answer.questionId(),
                answer.questionText(), answer.answer(), answer.createdAt());
    }
}
