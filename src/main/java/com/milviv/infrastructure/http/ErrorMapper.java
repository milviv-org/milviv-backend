package com.milviv.infrastructure.http;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.domain.exception.AppException;
import com.milviv.domain.exception.RetryAfter;
import com.milviv.domain.exception.ValidationException;

import jakarta.servlet.http.HttpServletResponse;

public class ErrorMapper {

    private static final Logger logger = LoggerFactory.getLogger(ErrorMapper.class);

    private final HttpStatusResolver httpStatusResolver;

    public ErrorMapper(HttpStatusResolver httpStatusResolver) {
        this.httpStatusResolver = httpStatusResolver;
    }

    private void logException(AppException ae) {
        switch (ae.getLogLevel()) {
            case INFO ->
                logger.info("[{}] {}", ae.getErrorCode(), ae.getMessage());
            case WARN ->
                logger.warn("[{}] {}", ae.getErrorCode(), ae.getMessage());
            case ERROR ->
                logger.error("[{}] {}", ae.getErrorCode(), ae.getMessage(), ae);
        }
    }

    public void handle(Throwable e, HttpContext ctx) throws IOException {
        // Разворачиваем InvocationTargetException от Reflection
        Throwable cause = (e instanceof InvocationTargetException ite)
                ? ite.getCause()
                : e;

        if (cause instanceof AppException ae) {
            logException(ae);

            Map<String, Object> ext = null;
            if (ae instanceof ValidationException ve) {
                ext = Map.of("errors", ve.getErrors()); // явно решаем что показать
            }
            if (ae instanceof RetryAfter ra && ra.getRetryAfter() != null) {
                // целые секунды с округлением вверх: раньше срока повтор снова получит отказ
                long seconds = Math.max(1, (ra.getRetryAfter().toMillis() + 999) / 1000);
                ext = Map.of("retryAfterSeconds", seconds);
                ctx.getResponse().setHeader("Retry-After", Long.toString(seconds));
            }

            ctx.json(
                    httpStatusResolver.resolve(ae),
                    new ErrorResponse(
                            ae.getErrorCode(),
                            ae.getMessage(),
                            ext,
                            ae.getTimestamp()
                    )
            );
        } else {
            ErrorResponse error = new ErrorResponse(
                    "INTERNAL_SERVER_ERROR",
                    "Internal server error",
                    Instant.now()
            );
            logger.error("[{}] {}", error.errorCode, error.message, cause);
            ctx.json(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    error
            );
        }
    }
}
