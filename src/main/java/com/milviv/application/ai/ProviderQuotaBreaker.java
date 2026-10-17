package com.milviv.application.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Предохранитель квоты провайдера (§8, G-8). Провайдер ответил «квота исчерпана» — следующие
 * AI_QUOTA_BREAKER_MINUTES новые запросы сразу получают AI_PROVIDER_QUOTA_EXHAUSTED: незачем
 * тратить вызовы и отправлять данные, которые точно получат отказ.
 *
 * В памяти: MILVIV — один процесс. После перезапуска предохранитель закрыт, первый же отказ
 * провайдера откроет его снова. При нескольких экземплярах его нужно перенести в БД.
 */
public class ProviderQuotaBreaker {

    private final Duration openFor;
    private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.MIN);

    public ProviderQuotaBreaker(AiLimits limits) {
        this.openFor = limits.request().quotaBreaker();
    }

    public void open(Instant now) {
        Instant until = now.plus(openFor);
        // параллельные отказы не сокращают уже открытый срок
        openUntil.accumulateAndGet(until, (current, next) -> next.isAfter(current) ? next : current);
    }

    /** @return пусто — закрыт, провайдер можно вызывать; иначе — сколько ждать */
    public Optional<Duration> retryAfter(Instant now) {
        Instant until = openUntil.get();
        return now.isBefore(until) ? Optional.of(Duration.between(now, until)) : Optional.empty();
    }
}
