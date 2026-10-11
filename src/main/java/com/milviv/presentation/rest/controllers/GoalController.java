package com.milviv.presentation.rest.controllers;

import java.util.List;
import java.util.UUID;

import com.milviv.application.goal.GoalCommands;
import com.milviv.application.goal.GoalView;
import com.milviv.domain.goal.GoalDetails;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.infrastructure.http.annotations.Put;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.Required;

/** Цели текущего пользователя. Все маршруты требуют входа. */
@Controller("/goals")
public class GoalController {

    record CreateGoalRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description
    ) {
        GoalDetails toDetails() {
            return new GoalDetails(title, description);
        }
    }

    /** Полная замена редактируемых полей. version — версия, которую видел клиент. */
    record UpdateGoalRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description,
            @Required Long version
    ) {
        GoalDetails toDetails() {
            return new GoalDetails(title, description);
        }
    }

    record GoalListResponse(List<GoalView> items) {}

    private final GoalCommands goalCommands;

    public GoalController(GoalCommands goalCommands) {
        this.goalCommands = goalCommands;
    }

    @Post("")
    public void create(HttpContext ctx) throws Exception {
        CreateGoalRequest body = ctx.body(CreateGoalRequest.class);
        ctx.json(201, goalCommands.create(ctx.userId(), body.toDetails()));
    }

    @Get("")
    public void list(HttpContext ctx) throws Exception {
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        ctx.json(200, new GoalListResponse(goalCommands.list(ctx.userId(), limit, offset)));
    }

    @Get("/{id}")
    public void get(HttpContext ctx) throws Exception {
        ctx.json(200, goalCommands.get(ctx.userId(), goalId(ctx)));
    }

    @Put("/{id}")
    public void update(HttpContext ctx) throws Exception {
        UpdateGoalRequest body = ctx.body(UpdateGoalRequest.class);
        ctx.json(200, goalCommands.update(ctx.userId(), goalId(ctx), body.toDetails(), body.version()));
    }

    @Delete("/{id}")
    public void delete(HttpContext ctx) {
        goalCommands.delete(ctx.userId(), goalId(ctx));
        ctx.status(204);
    }

    private static UUID goalId(HttpContext ctx) {
        return RequestParams.pathId(ctx, "Цель не найдена");
    }
}
