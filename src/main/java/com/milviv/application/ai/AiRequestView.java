package com.milviv.application.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.ai.AiRequest;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.DataAccess;
import com.milviv.domain.ai.OperationReport;
import com.milviv.domain.ai.PendingOperation;
import com.milviv.domain.ai.RefusalReason;

/**
 * AI-запрос для ответа клиенту. Поля исхода заполнены только у своего статуса.
 * PROPOSED с истёкшим сроком показывается как EXPIRED; в БД EXPIRED пишется при попытке решить.
 *
 * @param report          что сделано (G-21); пусто, если операций не было
 * @param pendingApproval карточка: изменения и удаления, которые ждут согласия; только у PROPOSED
 * @param dataAccessed    что AI прочитал: запрос, количество, причина (§6.6)
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
        List<PendingItem> pendingApproval,
        List<DataAccess> dataAccessed,
        Instant expiresAt,
        Instant createdAt
) {

    /** Пункт карточки — без деталей изменения (G-21): название из БД. */
    public record PendingItem(String itemId, PendingOperation.Op op, OperationReport.EntityType type, String title) {}

    public static AiRequestView from(AiRequest request, Instant now) {
        boolean expired = request.isExpired(now);
        AiRequestStatus status = expired ? AiRequestStatus.EXPIRED : request.getStatus();
        List<PendingItem> pending = status == AiRequestStatus.PROPOSED
                ? request.getPlan().operations().stream()
                        .map(op -> new PendingItem(op.itemId(), op.op(), op.type(), op.title()))
                        .toList()
                : List.of();
        return new AiRequestView(request.getId(), status, request.getSummary(), request.getAnswer(),
                request.getClarificationQuestion(), request.getErrorCode(), request.getRefusalReason(),
                request.getReport() == null ? List.of() : request.getReport().items(), pending,
                request.getDataAccessLog(), request.getExpiresAt(), request.getCreatedAt());
    }
}
