package com.milviv.domain.exception;

/**
 * Слишком много попыток за короткое время (например, неудачных входов).
 *
 * HTTP статус: 429 Too Many Requests.
 */
public class TooManyRequestsException extends DomainException {

    public TooManyRequestsException(String message) {
        super(message);
    }

    @Override
    public String getErrorCode() {
        return "TOO_MANY_REQUESTS";
    }
}
