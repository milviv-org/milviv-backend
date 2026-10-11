package com.milviv.presentation.rest.controllers;

import java.time.LocalDate;

import com.milviv.application.planning.CalendarQuery;
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
        LocalDate from = RequestParams.requiredDateQuery(ctx, "from");
        LocalDate to = RequestParams.requiredDateQuery(ctx, "to");
        ctx.json(200, calendarQuery.get(ctx.userId(), from, to));
    }
}
