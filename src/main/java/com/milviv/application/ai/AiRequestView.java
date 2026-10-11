package com.milviv.application.ai;

import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.ai.AiRequest;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.RefusalReason;

/** AI-запрос для ответа клиенту. Поля исхода заполнены только у своего статуса. */
public record AiRequestView(
        UUID requestId,
        AiRequestStatus status,
        String answer,
        String clarificationQuestion,
        String errorCode,
        RefusalReason refusalReason,
        Instant createdAt
) {

    public static AiRequestView from(AiRequest request) {
        return new AiRequestView(request.getId(), request.getStatus(), request.getAnswer(),
                request.getClarificationQuestion(), request.getErrorCode(), request.getRefusalReason(),
                request.getCreatedAt());
    }
}
