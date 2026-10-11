package com.milviv.infrastructure.http;

import java.util.Map;

import com.milviv.domain.exception.AppException;

import jakarta.servlet.http.HttpServletResponse;

public class HttpStatusResolver {

    private static final Map<String, Integer> STATUS_MAP = Map.of(
            "NOT_FOUND", HttpServletResponse.SC_NOT_FOUND,
            "VALIDATION", HttpServletResponse.SC_BAD_REQUEST,
            "UNAUTHORIZED", HttpServletResponse.SC_UNAUTHORIZED,
            "FORBIDDEN", HttpServletResponse.SC_FORBIDDEN,
            "CONFLICT", HttpServletResponse.SC_CONFLICT,
            "OPTIMISTIC_LOCK", HttpServletResponse.SC_CONFLICT,
            "TOO_MANY_REQUESTS", 429,
            "DATABASE", HttpServletResponse.SC_SERVICE_UNAVAILABLE
    );

    public int resolve(AppException e) {
        return STATUS_MAP.getOrDefault(e.getErrorCode(), 500);
    }
}
