package com.milviv.domain.exception;

import java.time.Duration;

/**
 * Ошибка, после которой запрос можно повторить через известное время (квоты, §11).
 * Клиент получает extension.retryAfterSeconds и заголовок Retry-After.
 */
public interface RetryAfter {

    /** @return null — срок неизвестен */
    Duration getRetryAfter();
}
