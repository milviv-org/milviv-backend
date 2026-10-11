package com.milviv.application.ai;

import java.time.Duration;

/**
 * Лимиты AI (§8). Значения приходят из AiConfig; в коде сценариев чисел нет.
 * Провайдерские настройки (модель, ключ, HTTP-таймауты, ретраи) — отдельно, вместе с адаптером.
 */
public record AiLimits(Request request, Data data, Plan plan) {

    /** До вызова AI и на весь запрос. */
    public record Request(
            int messageMaxLength,
            int requestsPerDay,
            int requestsPerMinute,
            int maxCallsPerRequest,
            Duration requestTimeout,
            Duration planTtl,
            int maxCreatedPerDay,
            Duration quotaBreaker
    ) {}

    /** Запросы данных из каталога. */
    public record Data(
            int maxRounds,
            int maxRequestsPerRound,
            int maxItems,
            int textMaxLength,
            int maxTokens
    ) {}

    /** План, предложенный AI. Лимиты AI могут быть строже ручных, но не мягче (§8). */
    public record Plan(
            int maxCreates,
            int maxUpdates,
            int maxDeletes,
            int maxProjects,
            int maxTasksPerNode,
            int maxTasksTotal,
            int maxRewards,
            int titleMaxLength,
            int descriptionMaxLength,
            int summaryMaxLength,
            int answerMaxLength,
            int dateHorizonYears,
            int rewardCostMax
    ) {}
}
