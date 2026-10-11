package com.milviv.presentation.rest.controllers;

import java.util.List;

import com.milviv.application.gamification.PointsEntryView;
import com.milviv.application.gamification.PointsQueries;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;

/** Баланс и история баллов текущего пользователя. Требует входа. */
@Controller("/points")
public class PointsController {

    record HistoryResponse(List<PointsEntryView> items) {}

    private final PointsQueries pointsQueries;

    public PointsController(PointsQueries pointsQueries) {
        this.pointsQueries = pointsQueries;
    }

    @Get("/balance")
    public void balance(HttpContext ctx) throws Exception {
        ctx.json(200, pointsQueries.balance(ctx.userId()));
    }

    @Get("/history")
    public void history(HttpContext ctx) throws Exception {
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        String hiddenPassword = ctx.header(RewardController.HIDDEN_PASSWORD_HEADER);
        ctx.json(200, new HistoryResponse(pointsQueries.history(ctx.userId(), hiddenPassword, limit, offset)));
    }
}
