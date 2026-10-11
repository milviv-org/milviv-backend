package com.milviv.presentation.rest.controllers;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.infrastructure.http.HttpContext;

/** Разбор параметров пути и query-строки, общий для контроллеров. */
final class RequestParams {

    static final int DEFAULT_PAGE_SIZE = 50;

    private RequestParams() {}

    /** Неверный id неотличим от несуществующего объекта — 404. */
    static UUID pathId(HttpContext ctx, String notFoundMessage) {
        try {
            return UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new NotFoundException(notFoundMessage);
        }
    }

    static int intQuery(HttpContext ctx, String name, int defaultValue) {
        String value = ctx.pathQuery(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new ValidationException(name, "Должно быть целым числом");
        }
    }

    static UUID uuidQuery(HttpContext ctx, String name) {
        String value = ctx.pathQuery(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(name, "Должен быть UUID");
        }
    }

    static <E extends Enum<E>> E enumQuery(HttpContext ctx, String name, Class<E> type) {
        String value = ctx.pathQuery(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            String allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            throw new ValidationException(name, "Допустимые значения: " + allowed);
        }
    }
}
