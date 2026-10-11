package com.milviv.application.reflection;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.reflection.ReflectionQuestion;

/** Вопрос для ответа клиенту. standard = true — общий, изменить нельзя. */
public record ReflectionQuestionView(UUID id, String text, boolean standard, Instant createdAt) {

    public static ReflectionQuestionView from(ReflectionQuestion question) {
        return new ReflectionQuestionView(question.getId(), question.getText(), question.isStandard(), question.getCreatedAt());
    }
}
