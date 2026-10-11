package com.milviv.presentation.rest.controllers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.milviv.application.task.CompleteTask;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.task.TaskView;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.TaskDetails;
import com.milviv.domain.task.TaskStatus;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.infrastructure.http.annotations.Put;
import com.milviv.shared.validation.annotation.annotations.EnumValue;
import com.milviv.shared.validation.annotation.annotations.Max;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.Min;
import com.milviv.shared.validation.annotation.annotations.Required;

/** Задачи текущего пользователя. Все маршруты требуют входа. */
@Controller("/tasks")
public class TaskController {

    static final int DEFAULT_PAGE_SIZE = 50;

    record CreateTaskRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description,
            @Required @EnumValue(enumValue = Difficulty.class) String difficulty,
            @Min(num = 1) @Max(num = 1000) Integer points,
            LocalDate startDate,
            LocalDate deadline,
            Instant reminderAt
    ) {
        TaskDetails toDetails() {
            return new TaskDetails(title, description, Difficulty.valueOf(difficulty), points, startDate, deadline, reminderAt);
        }
    }

    /** Полная замена редактируемых полей. version — версия, которую видел клиент. */
    record UpdateTaskRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description,
            @Required @EnumValue(enumValue = Difficulty.class) String difficulty,
            @Min(num = 1) @Max(num = 1000) Integer points,
            LocalDate startDate,
            LocalDate deadline,
            Instant reminderAt,
            @Required Long version
    ) {
        TaskDetails toDetails() {
            return new TaskDetails(title, description, Difficulty.valueOf(difficulty), points, startDate, deadline, reminderAt);
        }
    }

    record TaskListResponse(List<TaskView> items) {}

    private final TaskCommands taskCommands;
    private final CompleteTask completeTask;

    public TaskController(TaskCommands taskCommands, CompleteTask completeTask) {
        this.taskCommands = taskCommands;
        this.completeTask = completeTask;
    }

    @Post("")
    public void create(HttpContext ctx) throws Exception {
        CreateTaskRequest body = ctx.body(CreateTaskRequest.class);
        ctx.json(201, taskCommands.create(ctx.userId(), body.toDetails()));
    }

    @Get("")
    public void list(HttpContext ctx) throws Exception {
        TaskStatus status = parseStatus(ctx.pathQuery("status"));
        int limit = parseInt(ctx.pathQuery("limit"), "limit", DEFAULT_PAGE_SIZE);
        int offset = parseInt(ctx.pathQuery("offset"), "offset", 0);
        ctx.json(200, new TaskListResponse(taskCommands.list(ctx.userId(), status, limit, offset)));
    }

    @Get("/{id}")
    public void get(HttpContext ctx) throws Exception {
        ctx.json(200, taskCommands.get(ctx.userId(), taskId(ctx)));
    }

    @Put("/{id}")
    public void update(HttpContext ctx) throws Exception {
        UpdateTaskRequest body = ctx.body(UpdateTaskRequest.class);
        ctx.json(200, taskCommands.update(ctx.userId(), taskId(ctx), body.toDetails(), body.version()));
    }

    @Delete("/{id}")
    public void delete(HttpContext ctx) {
        taskCommands.delete(ctx.userId(), taskId(ctx));
        ctx.status(204);
    }

    @Post("/{id}/complete")
    public void complete(HttpContext ctx) throws Exception {
        ctx.json(200, completeTask.execute(ctx.userId(), taskId(ctx)));
    }

    @Post("/{id}/cancel")
    public void cancel(HttpContext ctx) throws Exception {
        ctx.json(200, taskCommands.cancel(ctx.userId(), taskId(ctx)));
    }

    /** Неверный id неотличим от несуществующей задачи — 404. */
    private static UUID taskId(HttpContext ctx) {
        try {
            return UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new NotFoundException("Задача не найдена");
        }
    }

    private static TaskStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("status", "Допустимые значения: ACTIVE, COMPLETED, CANCELLED");
        }
    }

    private static int parseInt(String value, String field, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new ValidationException(field, "Должно быть целым числом");
        }
    }
}
