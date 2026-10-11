package com.milviv.application.auth;

/**
 * Ограничение неудачных попыток входа — защита от подбора пароля.
 * Ключ — нормализованный email.
 */
public interface LoginAttemptLimiter {

    /** Бросает TooManyRequestsException, если попытки для ключа исчерпаны. */
    void checkAllowed(String key);

    void recordFailure(String key);

    void reset(String key);
}
