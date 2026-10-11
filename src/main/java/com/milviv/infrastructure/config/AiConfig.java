package com.milviv.infrastructure.config;

import java.time.Duration;

import com.milviv.application.ai.AiLimits;
import com.milviv.infrastructure.container.Inject;

/**
 * Лимиты AI (§8 концепции). Источники и приоритет — как в AppConfig (ConfigLoader):
 * env AI_* → .env → application.properties ai.* → дефолт в коде.
 * Читается один раз при старте; некорректное значение — приложение не запускается.
 *
 * Провайдерские ключи (AI_PROVIDER, AI_MODEL, GEMINI_API_KEY, HTTP-таймауты) появятся вместе с адаптером.
 */
public class AiConfig {

    private final AiLimits limits;

    @Inject
    public AiConfig(ConfigLoader config) {
        AiLimits.Request request = new AiLimits.Request(
                positive(config, "AI_MESSAGE_MAX_LENGTH", "ai.message.max.length", 2000),
                positive(config, "AI_USER_REQUESTS_PER_DAY", "ai.user.requests.per.day", 20),
                positive(config, "AI_USER_REQUESTS_PER_MINUTE", "ai.user.requests.per.minute", 3),
                positive(config, "AI_MAX_CALLS_PER_REQUEST", "ai.max.calls.per.request", 4),
                Duration.ofMillis(positive(config, "AI_REQUEST_TIMEOUT_MS", "ai.request.timeout.ms", 90_000)),
                Duration.ofHours(positive(config, "AI_PLAN_TTL_HOURS", "ai.plan.ttl.hours", 24)),
                positive(config, "AI_MAX_CREATED_PER_DAY", "ai.max.created.per.day", 200),
                Duration.ofMinutes(positive(config, "AI_QUOTA_BREAKER_MINUTES", "ai.quota.breaker.minutes", 60)));

        AiLimits.Data data = new AiLimits.Data(
                positive(config, "AI_MAX_DATA_ROUNDS", "ai.max.data.rounds", 2),
                positive(config, "AI_MAX_DATA_REQUESTS_PER_ROUND", "ai.max.data.requests.per.round", 3),
                positive(config, "AI_DATA_MAX_ITEMS", "ai.data.max.items", 100),
                positive(config, "AI_DATA_TEXT_MAX_LENGTH", "ai.data.text.max.length", 1000),
                positive(config, "AI_DATA_MAX_TOKENS", "ai.data.max.tokens", 16_000));

        AiLimits.Plan plan = new AiLimits.Plan(
                positive(config, "AI_MAX_CREATES", "ai.max.creates", 40),
                positive(config, "AI_MAX_UPDATES", "ai.max.updates", 20),
                positive(config, "AI_MAX_DELETES", "ai.max.deletes", 10),
                positive(config, "AI_MAX_PROJECTS", "ai.max.projects", 10),
                positive(config, "AI_MAX_TASKS_PER_NODE", "ai.max.tasks.per.node", 10),
                positive(config, "AI_MAX_TASKS_TOTAL", "ai.max.tasks.total", 30),
                positive(config, "AI_MAX_REWARDS", "ai.max.rewards", 3),
                positive(config, "AI_TITLE_MAX_LENGTH", "ai.title.max.length", 100),
                positive(config, "AI_DESCRIPTION_MAX_LENGTH", "ai.description.max.length", 1000),
                positive(config, "AI_SUMMARY_MAX_LENGTH", "ai.summary.max.length", 500),
                positive(config, "AI_ANSWER_MAX_LENGTH", "ai.answer.max.length", 2000),
                positive(config, "AI_DATE_HORIZON_YEARS", "ai.date.horizon.years", 2),
                positive(config, "AI_REWARD_COST_MAX", "ai.reward.cost.max", 100_000));

        validate(request, plan);
        this.limits = new AiLimits(request, data, plan);
    }

    public AiLimits toLimits() {
        return limits;
    }

    private static int positive(ConfigLoader config, String envKey, String propertyKey, int defaultValue) {
        int value = config.getInt(envKey, propertyKey, defaultValue);
        if (value <= 0) {
            throw new IllegalStateException(envKey + " must be > 0, got " + value);
        }
        return value;
    }

    /** Согласованность значений между собой: min ≤ max. */
    private static void validate(AiLimits.Request request, AiLimits.Plan plan) {
        if (request.requestsPerMinute() > request.requestsPerDay()) {
            throw new IllegalStateException("AI_USER_REQUESTS_PER_MINUTE must be <= AI_USER_REQUESTS_PER_DAY");
        }
        if (plan.maxTasksPerNode() > plan.maxTasksTotal()) {
            throw new IllegalStateException("AI_MAX_TASKS_PER_NODE must be <= AI_MAX_TASKS_TOTAL");
        }
        if (plan.maxTasksTotal() > plan.maxCreates() || plan.maxProjects() > plan.maxCreates()
                || plan.maxRewards() > plan.maxCreates()) {
            throw new IllegalStateException("AI_MAX_TASKS_TOTAL, AI_MAX_PROJECTS and AI_MAX_REWARDS must be <= AI_MAX_CREATES");
        }
        if (plan.maxCreates() > request.maxCreatedPerDay()) {
            throw new IllegalStateException("AI_MAX_CREATES must be <= AI_MAX_CREATED_PER_DAY");
        }
    }

    @Override
    public String toString() {
        return "AiConfig{" + limits + "}";
    }
}
