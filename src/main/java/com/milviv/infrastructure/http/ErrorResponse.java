package com.milviv.infrastructure.http;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    public final String errorCode;
    public final String message;
    public final Map<String, Object> extension;
    public final Instant timestamp;

    public ErrorResponse(
            String errorCode,
            String message,
            Map<String, Object> extension,
            Instant timestamp
    ) {
        this.errorCode = errorCode;
        this.message = message;
        this.extension = extension;
        this.timestamp = timestamp;
    }

    public ErrorResponse(String errorCode, String message, Instant timestamp) {
        this(errorCode, message, null, timestamp);
    }

}
