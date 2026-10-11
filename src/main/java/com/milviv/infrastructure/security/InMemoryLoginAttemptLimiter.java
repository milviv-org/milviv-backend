package com.milviv.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.milviv.application.auth.LoginAttemptLimiter;
import com.milviv.domain.exception.TooManyRequestsException;

/**
 * Не больше maxAttempts неудачных входов на email за окно window.
 * Хранится в памяти процесса: при перезапуске счётчики обнуляются,
 * при нескольких экземплярах сервера каждый считает отдельно.
 */
public class InMemoryLoginAttemptLimiter implements LoginAttemptLimiter {

    /** Порог, после которого из памяти вычищаются истёкшие записи. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Attempts(int failures, Instant windowStart) {}

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;

    public InMemoryLoginAttemptLimiter(int maxAttempts, Duration window, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.clock = clock;
    }

    @Override
    public void checkAllowed(String key) {
        Attempts current = attempts.get(key);
        if (current != null && !isExpired(current, clock.instant()) && current.failures() >= maxAttempts) {
            throw new TooManyRequestsException("Слишком много попыток входа. Попробуйте позже");
        }
    }

    @Override
    public void recordFailure(String key) {
        Instant now = clock.instant();
        attempts.compute(key, (k, current) ->
                current == null || isExpired(current, now)
                        ? new Attempts(1, now)
                        : new Attempts(current.failures() + 1, current.windowStart()));

        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.values().removeIf(a -> isExpired(a, now));
        }
    }

    @Override
    public void reset(String key) {
        attempts.remove(key);
    }

    private boolean isExpired(Attempts value, Instant now) {
        return !now.isBefore(value.windowStart().plus(window));
    }
}
