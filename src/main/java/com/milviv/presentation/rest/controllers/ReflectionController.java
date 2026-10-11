package com.milviv.presentation.rest.controllers;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.milviv.application.reflection.ReflectionAnswerView;
import com.milviv.application.reflection.ReflectionQuestionCommands;
import com.milviv.application.reflection.ReflectionQuestionView;
import com.milviv.application.reflection.TaskReflectionCommands;
import com.milviv.domain.exception.ValidationException;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.infrastructure.http.annotations.Put;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.Required;

/** Рефлексия текущего пользователя: вопросы, вопросы задачи, ответы. Все маршруты требуют входа. */
@Controller("/reflection")
public class ReflectionController {

    record QuestionRequest(@Required @MaxLength(length = 500) String text) {}

    /** Пустой список отключает рефлексию для задачи. */
    record TaskQuestionsRequest(List<UUID> questionIds) {}

    record AnswerItem(UUID questionId, String answer) {}

    record AnswersRequest(@Required List<AnswerItem> answers) {}

    record QuestionListResponse(List<ReflectionQuestionView> items) {}

    record AnswerListResponse(List<ReflectionAnswerView> items) {}

    static final int MAX_ANSWER_LENGTH = 5000;

    private final ReflectionQuestionCommands questionCommands;
    private final TaskReflectionCommands taskReflection;

    public ReflectionController(ReflectionQuestionCommands questionCommands, TaskReflectionCommands taskReflection) {
        this.questionCommands = questionCommands;
        this.taskReflection = taskReflection;
    }

    // ─── Вопросы ──────────────────────────────────────────────────────────────

    @Get("/questions")
    public void listQuestions(HttpContext ctx) throws Exception {
        ctx.json(200, new QuestionListResponse(questionCommands.list(ctx.userId())));
    }

    @Post("/questions")
    public void createQuestion(HttpContext ctx) throws Exception {
        QuestionRequest body = ctx.body(QuestionRequest.class);
        ctx.json(201, questionCommands.create(ctx.userId(), body.text()));
    }

    @Put("/questions/{id}")
    public void updateQuestion(HttpContext ctx) throws Exception {
        QuestionRequest body = ctx.body(QuestionRequest.class);
        ctx.json(200, questionCommands.update(ctx.userId(), questionId(ctx), body.text()));
    }

    @Delete("/questions/{id}")
    public void deleteQuestion(HttpContext ctx) {
        questionCommands.delete(ctx.userId(), questionId(ctx));
        ctx.status(204);
    }

    // ─── Рефлексия задачи ─────────────────────────────────────────────────────

    @Get("/tasks/{id}")
    public void getForTask(HttpContext ctx) throws Exception {
        ctx.json(200, taskReflection.get(ctx.userId(), taskId(ctx)));
    }

    @Put("/tasks/{id}/questions")
    public void setTaskQuestions(HttpContext ctx) throws Exception {
        TaskQuestionsRequest body = ctx.body(TaskQuestionsRequest.class);
        List<UUID> ids = body.questionIds() == null ? List.of() : body.questionIds();
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("questionIds", "Пустой id вопроса");
        }
        ctx.json(200, taskReflection.setQuestions(ctx.userId(), taskId(ctx), ids));
    }

    @Post("/tasks/{id}/answers")
    public void answer(HttpContext ctx) throws Exception {
        AnswersRequest body = ctx.body(AnswersRequest.class);
        List<TaskReflectionCommands.AnswerInput> input = body.answers().stream()
                .map(ReflectionController::toInput)
                .toList();
        ctx.json(200, taskReflection.answer(ctx.userId(), taskId(ctx), input));
    }

    @Get("/answers")
    public void listAnswers(HttpContext ctx) throws Exception {
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        ctx.json(200, new AnswerListResponse(taskReflection.listAnswers(ctx.userId(), limit, offset)));
    }

    /** Аннотации не проверяют элементы списка — проверяем здесь. */
    private static TaskReflectionCommands.AnswerInput toInput(AnswerItem item) {
        if (item == null || item.questionId() == null) {
            throw new ValidationException("answers", "Укажите questionId");
        }
        if (item.answer() == null || item.answer().isBlank()) {
            throw new ValidationException("answers", "Ответ не может быть пустым");
        }
        if (item.answer().length() > MAX_ANSWER_LENGTH) {
            throw new ValidationException("answers", "Ответ длиннее " + MAX_ANSWER_LENGTH + " символов");
        }
        return new TaskReflectionCommands.AnswerInput(item.questionId(), item.answer());
    }

    private static UUID questionId(HttpContext ctx) {
        return RequestParams.pathId(ctx, "Вопрос не найден");
    }

    private static UUID taskId(HttpContext ctx) {
        return RequestParams.pathId(ctx, "Задача не найдена");
    }
}
