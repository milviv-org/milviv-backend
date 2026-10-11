package com.milviv.application.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.ai.AiRequest;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.DataAccess;
import com.milviv.domain.ai.OperationReport;
import com.milviv.domain.ai.RefusalReason;

/**
 * AI-запрос для ответа клиенту. Поля исхода заполнены только у своего статуса.
 *
 * @param report       что сделано (G-21); пусто, если операций не было
 * @param dataAccessed что AI прочитал: запрос, количество, причина (§6.6)
 */
public record AiRequestView(
        UUID requestId,
        AiRequestStatus status,
        String summary,
        String answer,
        String clarificationQuestion,
        String errorCode,
        RefusalReason refusalReason,
        List<OperationReport.Item> report,
        List<DataAccess> dataAccessed,
        Instant createdAt
) {

    public static AiRequestView from(AiRequest request) {
        return new AiRequestView(request.getId(), request.getStatus(), request.getSummary(), request.getAnswer(),
                request.getClarificationQuestion(), request.getErrorCode(), request.getRefusalReason(),
                request.getReport() == null ? List.of() : request.getReport().items(), request.getDataAccessLog(),
                request.getCreatedAt());
    }
}
