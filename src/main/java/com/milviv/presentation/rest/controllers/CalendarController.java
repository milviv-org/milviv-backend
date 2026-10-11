package com.milviv.presentation.rest.controllers;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.milviv.application.planning.CalendarQuery;
import com.milviv.domain.exception.ValidationException;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;

/** Календарь текущего пользователя. Требует входа. */
@Controller("/calendar")
public class CalendarController {

    private final CalendarQuery calendarQuery;

    public CalendarController(CalendarQuery calendarQuery) {
        this.calendarQuery = calendarQuery;
    }

    @Get("")
    public void get(HttpContext ctx) throws Exception {
        LocalDate from = requiredDate(ctx, "from");
        LocalDate to = requiredDate(ctx, "to");
        ctx.json(200, calendarQuery.get(ctx.userId(), from, to));
    }

    private static LocalDate requiredDate(HttpContext ctx, String name) {
        String value = ctx.pathQuery(name);
        if (value == null || value.isBlank()) {
            throw new ValidationException(name, "Обязательный параметр, формат YYYY-MM-DD");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ValidationException(name, "Формат YYYY-MM-DD");
        }
    }
}
