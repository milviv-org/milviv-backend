package com.milviv.presentation.rest.controllers;

import java.util.List;

import com.milviv.application.statistics.StatisticsQueries;
import com.milviv.application.statistics.StatisticsQueries.GoalProgressView;
import com.milviv.application.statistics.StatisticsQueries.ProjectProgressView;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;

/** Статистика текущего пользователя. Все маршруты требуют входа. */
@Controller("/statistics")
public class StatisticsController {

    record ProjectsResponse(List<ProjectProgressView> items) {}

    record GoalsResponse(List<GoalProgressView> items) {}

    private final StatisticsQueries statistics;

    public StatisticsController(StatisticsQueries statistics) {
        this.statistics = statistics;
    }

    @Get("/summary")
    public void summary(HttpContext ctx) throws Exception {
        ctx.json(200, statistics.summary(ctx.userId(),
                RequestParams.requiredDateQuery(ctx, "from"),
                RequestParams.requiredDateQuery(ctx, "to")));
    }

    @Get("/projects")
    public void projects(HttpContext ctx) throws Exception {
        ProjectStatus status = RequestParams.enumQuery(ctx, "status", ProjectStatus.class);
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        ctx.json(200, new ProjectsResponse(statistics.projects(ctx.userId(), status, limit, offset)));
    }

    @Get("/goals")
    public void goals(HttpContext ctx) throws Exception {
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        ctx.json(200, new GoalsResponse(statistics.goals(ctx.userId(), limit, offset)));
    }
}
