package com.milviv.application.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.ai.AiRequestRepository;
import com.milviv.domain.ai.AiRequestRepository.Creations;
import com.milviv.domain.exception.AiRequestException;

/**
 * Лимиты на пользователя (§8, T-1, T-4). Бесплатная квота провайдера — общая, поэтому
 * один пользователь не должен её исчерпать; а лимит созданий в сутки — единственная защита
 * от инъекции «создай 10 000 задач»: создания не подтверждаются (G-25).
 *
 * Окна скользящие: часового пояса пользователя нет (K-4), и «через сколько можно» честнее,
 * чем «до полуночи UTC». Считаются все записанные запросы, в том числе FAILED; повтор с тем же
 * Idempotency-Key и отказы до записи — нет.
 *
 * Вызывается в транзакции 1 под блокировкой строки пользователя — два запроса не проскочат оба.
 */
public class AiUsageQuota {

    static final Duration MINUTE = Duration.ofMinutes(1);
    static final Duration DAY = Duration.ofDays(1);

    private final AiRequestRepository requests;
    private final AiLimits.Request limits;

    public AiUsageQuota(AiRequestRepository requests, AiLimits limits) {
        this.requests = requests;
        this.limits = limits.request();
    }

    /** @param createsLeft сколько ещё можно создать через AI в текущих сутках, > 0 */
    public record Allowance(int createsLeft) {}

    /** @throws AiRequestException AI_QUOTA_EXCEEDED с retryAfter */
    public Allowance check(UUID userId, Instant now) {
        List<Instant> starts = requests.findStartTimesSince(userId, now.minus(DAY));
        List<Instant> lastMinute = starts.stream().filter(start -> start.isAfter(now.minus(MINUTE))).toList();
        requireBelow(lastMinute, limits.requestsPerMinute(), MINUTE, now);
        requireBelow(starts, limits.requestsPerDay(), DAY, now);

        List<Creations> creations = requests.findCreationsSince(userId, now.minus(DAY));
        int created = creations.stream().mapToInt(Creations::count).sum();
        int left = limits.maxCreatedPerDay() - created;
        if (left <= 0) {
            throw AiRequestException.quotaExceeded(untilCreationsFree(creations, created, now));
        }
        return new Allowance(left);
    }

    /** Лимит освободится, когда из окна выйдет столько старых запросов, чтобы их стало меньше limit. */
    private static void requireBelow(List<Instant> startsOldestFirst, int limit, Duration window, Instant now) {
        if (startsOldestFirst.size() >= limit) {
            Instant freeAt = startsOldestFirst.get(startsOldestFirst.size() - limit).plus(window);
            throw AiRequestException.quotaExceeded(until(freeAt, now));
        }
    }

    private Duration untilCreationsFree(List<Creations> oldestFirst, int created, Instant now) {
        int total = created;
        for (Creations batch : oldestFirst) {
            total -= batch.count();
            if (total < limits.maxCreatedPerDay()) {
                return until(batch.startedAt().plus(DAY), now);
            }
        }
        return null;
    }

    private static Duration until(Instant moment, Instant now) {
        Duration left = Duration.between(now, moment);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
