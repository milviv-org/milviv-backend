package com.milviv.presentation.rest.controllers;

import java.util.List;
import java.util.UUID;

import com.milviv.application.ai.AiMemoryCommands;
import com.milviv.application.ai.AiMemoryCommands.AiMemoryView;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.shared.validation.annotation.annotations.Required;

/** Память AI: выводы пользователя для будущих запросов. Все маршруты требуют входа и согласия. */
@Controller("/ai/memories")
public class AiMemoryController {

    /** Длина проверяется в сценарии: предел — в конфиге AI. */
    record MemoryRequest(@Required String content) {}

    record AiMemoryListResponse(List<AiMemoryView> items) {}

    private final AiMemoryCommands memoryCommands;

    public AiMemoryController(AiMemoryCommands memoryCommands) {
        this.memoryCommands = memoryCommands;
    }

    @Get("")
    public void list(HttpContext ctx) throws Exception {
        ctx.json(200, new AiMemoryListResponse(memoryCommands.list(ctx.userId())));
    }

    @Post("")
    public void create(HttpContext ctx) throws Exception {
        MemoryRequest body = ctx.body(MemoryRequest.class);
        ctx.json(201, memoryCommands.create(ctx.userId(), body.content()));
    }

    @Delete("/{id}")
    public void delete(HttpContext ctx) {
        UUID memoryId = RequestParams.pathId(ctx, "Запись памяти не найдена");
        memoryCommands.delete(ctx.userId(), memoryId);
        ctx.status(204);
    }
}
