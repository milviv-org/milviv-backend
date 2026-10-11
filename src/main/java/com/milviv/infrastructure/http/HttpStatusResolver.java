package com.milviv.infrastructure.http;

import java.util.Map;

import com.milviv.domain.exception.AppException;

import jakarta.servlet.http.HttpServletResponse;

public class HttpStatusResolver {

    /** Map.ofEntries: у Map.of предел — 10 пар, кодов станет больше. */
    private static final Map<String, Integer> STATUS_MAP = Map.ofEntries(
            Map.entry("NOT_FOUND", HttpServletResponse.SC_NOT_FOUND),
            Map.entry("VALIDATION", HttpServletResponse.SC_BAD_REQUEST),
            Map.entry("UNAUTHORIZED", HttpServletResponse.SC_UNAUTHORIZED),
            Map.entry("FORBIDDEN", HttpServletResponse.SC_FORBIDDEN),
            Map.entry("CONFLICT", HttpServletResponse.SC_CONFLICT),
            Map.entry("OPTIMISTIC_LOCK", HttpServletResponse.SC_CONFLICT),
            Map.entry("TOO_MANY_REQUESTS", 429),
            Map.entry("DATABASE", HttpServletResponse.SC_SERVICE_UNAVAILABLE),
            Map.entry("AI_CONSENT_REQUIRED", HttpServletResponse.SC_FORBIDDEN)
    );

    public int resolve(AppException e) {
        return STATUS_MAP.getOrDefault(e.getErrorCode(), 500);
    }
}
