package com.milviv.presentation.rest.controllers;

import java.util.List;
import java.util.UUID;

import com.milviv.application.ai.AiRequestView;
import com.milviv.application.ai.DecideAiProposal;
import com.milviv.application.ai.HandleAiRequest;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.shared.validation.annotation.annotations.Required;

/** Запросы к AI-ассистенту (§6.7). Все маршруты требуют входа и согласия на использование данных. */
@Controller("/ai/requests")
public class AiRequestController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Длина проверяется в сценарии: предел — в конфиге AI. */
    record MessageRequest(@Required String message) {}

    record AiRequestListResponse(List<AiRequestView> items) {}

    /** @param approvedChangeIds itemId из карточки; пустой список — отклонить всё */
    record ExecutionRequest(List<String> approvedChangeIds) {}

    private final HandleAiRequest handleAiRequest;
    private final DecideAiProposal decideAiProposal;

    public AiRequestController(HandleAiRequest handleAiRequest, DecideAiProposal decideAiProposal) {
        this.handleAiRequest = handleAiRequest;
        this.decideAiProposal = decideAiProposal;
    }

    /** Новый запрос — 201, повтор с тем же Idempotency-Key — 200 с тем же телом. */
    @Post("")
    public void create(HttpContext ctx) throws Exception {
        MessageRequest body = ctx.body(MessageRequest.class);
        HandleAiRequest.Result result = handleAiRequest.execute(ctx.userId(), ctx.header(IDEMPOTENCY_KEY_HEADER), body.message());
        ctx.json(result.created() ? 201 : 200, result.request());
    }

    @Get("")
    public void list(HttpContext ctx) throws Exception {
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        ctx.json(200, new AiRequestListResponse(handleAiRequest.list(ctx.userId(), limit, offset)));
    }

    @Get("/{id}")
    public void get(HttpContext ctx) throws Exception {
        UUID requestId = RequestParams.pathId(ctx, "Запрос не найден");
        ctx.json(200, handleAiRequest.get(ctx.userId(), requestId));
    }

    /** Исполнить отмеченные изменения и удаления. Повтор с тем же набором — 200 с тем же телом. */
    @Post("/{id}/execution")
    public void execution(HttpContext ctx) throws Exception {
        UUID requestId = RequestParams.pathId(ctx, "Запрос не найден");
        ExecutionRequest body = ctx.body(ExecutionRequest.class);
        ctx.json(200, decideAiProposal.execute(ctx.userId(), requestId, body.approvedChangeIds()));
    }

    /** Отклонить всё: изменения и удаления не исполняются, созданное остаётся. */
    @Post("/{id}/rejection")
    public void rejection(HttpContext ctx) throws Exception {
        UUID requestId = RequestParams.pathId(ctx, "Запрос не найден");
        ctx.json(200, decideAiProposal.reject(ctx.userId(), requestId));
    }
}
