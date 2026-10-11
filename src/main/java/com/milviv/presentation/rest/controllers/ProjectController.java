package com.milviv.presentation.rest.controllers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.milviv.application.project.ProjectCommands;
import com.milviv.application.project.ProjectView;
import com.milviv.domain.project.ProjectDetails;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.infrastructure.http.annotations.Put;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.Required;

/** Проекты текущего пользователя. Все маршруты требуют входа. */
@Controller("/projects")
public class ProjectController {

    record CreateProjectRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description,
            LocalDate deadline,
            UUID goalId
    ) {
        ProjectDetails toDetails() {
            return new ProjectDetails(goalId, title, description, deadline);
        }
    }

    /** Полная замена редактируемых полей. version — версия, которую видел клиент. */
    record UpdateProjectRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description,
            LocalDate deadline,
            UUID goalId,
            @Required Long version
    ) {
        ProjectDetails toDetails() {
            return new ProjectDetails(goalId, title, description, deadline);
        }
    }

    record ProjectListResponse(List<ProjectView> items) {}

    private final ProjectCommands projectCommands;

    public ProjectController(ProjectCommands projectCommands) {
        this.projectCommands = projectCommands;
    }

    @Post("")
    public void create(HttpContext ctx) throws Exception {
        CreateProjectRequest body = ctx.body(CreateProjectRequest.class);
        ctx.json(201, projectCommands.create(ctx.userId(), body.toDetails()));
    }

    @Get("")
    public void list(HttpContext ctx) throws Exception {
        ProjectStatus status = RequestParams.enumQuery(ctx, "status", ProjectStatus.class);
        UUID goalId = RequestParams.uuidQuery(ctx, "goalId");
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        ctx.json(200, new ProjectListResponse(projectCommands.list(ctx.userId(), status, goalId, limit, offset)));
    }

    @Get("/{id}")
    public void get(HttpContext ctx) throws Exception {
        ctx.json(200, projectCommands.get(ctx.userId(), projectId(ctx)));
    }

    @Put("/{id}")
    public void update(HttpContext ctx) throws Exception {
        UpdateProjectRequest body = ctx.body(UpdateProjectRequest.class);
        ctx.json(200, projectCommands.update(ctx.userId(), projectId(ctx), body.toDetails(), body.version()));
    }

    @Delete("/{id}")
    public void delete(HttpContext ctx) {
        projectCommands.delete(ctx.userId(), projectId(ctx));
        ctx.status(204);
    }

    @Post("/{id}/complete")
    public void complete(HttpContext ctx) throws Exception {
        ctx.json(200, projectCommands.complete(ctx.userId(), projectId(ctx)));
    }

    @Post("/{id}/cancel")
    public void cancel(HttpContext ctx) throws Exception {
        ctx.json(200, projectCommands.cancel(ctx.userId(), projectId(ctx)));
    }

    private static UUID projectId(HttpContext ctx) {
        return RequestParams.pathId(ctx, "Проект не найден");
    }
}
