package com.milviv.presentation.rest.controllers;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.milviv.application.planning.PlanCommands;
import com.milviv.application.planning.PlanView;
import com.milviv.domain.planning.PlanDetails;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.infrastructure.http.annotations.Put;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.Required;

/** Планы текущего пользователя. Все маршруты требуют входа. */
@Controller("/plans")
public class PlanController {

    /** Тело POST и PUT: PUT заменяет все поля. */
    record PlanRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description,
            UUID taskId,
            LocalDate planDate,
            Set<DayOfWeek> daysOfWeek,
            @Required LocalTime timeFrom,
            @Required LocalTime timeTo
    ) {
        PlanDetails toDetails() {
            return new PlanDetails(taskId, title, description, planDate, daysOfWeek, timeFrom, timeTo);
        }
    }

    record PlanListResponse(List<PlanView> items) {}

    private final PlanCommands planCommands;

    public PlanController(PlanCommands planCommands) {
        this.planCommands = planCommands;
    }

    @Post("")
    public void create(HttpContext ctx) throws Exception {
        PlanRequest body = ctx.body(PlanRequest.class);
        ctx.json(201, planCommands.create(ctx.userId(), body.toDetails()));
    }

    @Get("")
    public void list(HttpContext ctx) throws Exception {
        UUID taskId = RequestParams.uuidQuery(ctx, "taskId");
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        ctx.json(200, new PlanListResponse(planCommands.list(ctx.userId(), taskId, limit, offset)));
    }

    @Get("/{id}")
    public void get(HttpContext ctx) throws Exception {
        ctx.json(200, planCommands.get(ctx.userId(), planId(ctx)));
    }

    @Put("/{id}")
    public void update(HttpContext ctx) throws Exception {
        PlanRequest body = ctx.body(PlanRequest.class);
        ctx.json(200, planCommands.update(ctx.userId(), planId(ctx), body.toDetails()));
    }

    @Delete("/{id}")
    public void delete(HttpContext ctx) {
        planCommands.delete(ctx.userId(), planId(ctx));
        ctx.status(204);
    }

    private static UUID planId(HttpContext ctx) {
        return RequestParams.pathId(ctx, "План не найден");
    }
}
