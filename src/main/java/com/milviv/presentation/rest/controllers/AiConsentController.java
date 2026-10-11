package com.milviv.presentation.rest.controllers;

import com.milviv.application.ai.AiConsentService;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;

/** Согласие на использование данных для AI (§6.10). Требует входа. */
@Controller("/ai/consent")
public class AiConsentController {

    private final AiConsentService consentService;

    public AiConsentController(AiConsentService consentService) {
        this.consentService = consentService;
    }

    @Get("")
    public void status(HttpContext ctx) throws Exception {
        ctx.json(200, consentService.status(ctx.userId()));
    }

    /** Первое согласие — 201, повтор — 200 с исходной датой. Тела нет. */
    @Post("")
    public void accept(HttpContext ctx) throws Exception {
        AiConsentService.AcceptResult result = consentService.accept(ctx.userId());
        ctx.json(result.created() ? 201 : 200, result.consent());
    }
}
