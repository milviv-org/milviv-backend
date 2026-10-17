package com.milviv.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

import com.milviv.application.ai.AiLimits;
import com.milviv.infrastructure.ai.gemini.GeminiSettings;
import com.milviv.infrastructure.container.Inject;

/**
 * Лимиты AI (§8 концепции). Источники и приоритет — как в AppConfig (ConfigLoader):
 * env AI_* → .env → application.properties ai.* → дефолт в коде.
 * Читается один раз при старте; некорректное значение — приложение не запускается.
 *
 * Провайдер: AI_PROVIDER=none (по умолчанию) — AI недоступен, приложение работает без ключа;
 * gemini — нужны AI_MODEL и GEMINI_API_KEY. Ключ — только из env или .env (OC-01).
 */
public class AiConfig {

    public static final String PROVIDER_NONE = "none";
    public static final String PROVIDER_GEMINI = "gemini";

    /** Имя модели попадает в URL — только безопасные символы (T-16). */
    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    private final AiLimits limits;
    private final String provider;
    private final GeminiSettings gemini;

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
                positive(config, "AI_CLARIFICATION_MAX_LENGTH", "ai.clarification.max.length", 300),
                positive(config, "AI_DATA_REQUEST_REASON_MAX_LENGTH", "ai.data.request.reason.max.length", 200),
                positive(config, "AI_DATE_HORIZON_YEARS", "ai.date.horizon.years", 2),
                positive(config, "AI_REWARD_COST_MAX", "ai.reward.cost.max", 100_000));

        AiLimits.Memory memory = new AiLimits.Memory(
                positive(config, "AI_MEMORY_MAX_ITEMS", "ai.memory.max.items", 20),
                positive(config, "AI_MEMORY_MAX_LENGTH", "ai.memory.max.length", 500));

        validate(request, plan);
        this.limits = new AiLimits(request, data, plan, memory);

        this.provider = config.get("AI_PROVIDER", "ai.provider", PROVIDER_NONE).strip().toLowerCase();
        this.gemini = switch (provider) {
            case PROVIDER_NONE -> null;
            case PROVIDER_GEMINI -> gemini(config, request.requestTimeout());
            default -> throw new IllegalStateException("AI_PROVIDER must be " + PROVIDER_NONE + " or "
                    + PROVIDER_GEMINI + ", got " + provider);
        };
    }

    public AiLimits toLimits() {
        return limits;
    }

    public String provider() {
        return provider;
    }

    /** @throws IllegalStateException провайдер не gemini */
    public GeminiSettings toGeminiSettings() {
        if (gemini == null) {
            throw new IllegalStateException("AI_PROVIDER is not " + PROVIDER_GEMINI);
        }
        return gemini;
    }

    private static GeminiSettings gemini(ConfigLoader config, Duration requestTimeout) {
        String apiKey = config.getSecret("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required when AI_PROVIDER=" + PROVIDER_GEMINI);
        }
        String model = config.get("AI_MODEL", "ai.model", "").strip();
        if (!MODEL.matcher(model).matches()) {
            throw new IllegalStateException("AI_MODEL is required when AI_PROVIDER=" + PROVIDER_GEMINI
                    + " (letters, digits, '.', '_', '-')");
        }
        URI baseUrl = URI.create(config.get("AI_GEMINI_BASE_URL", "ai.gemini.base.url",
                "https://generativelanguage.googleapis.com").strip());
        if (!"https".equals(baseUrl.getScheme()) && !"http".equals(baseUrl.getScheme())) {
            throw new IllegalStateException("AI_GEMINI_BASE_URL must be http(s), got " + baseUrl);
        }
        Duration callTimeout = Duration.ofMillis(positive(config, "AI_CALL_TIMEOUT_MS", "ai.call.timeout.ms", 30_000));
        if (callTimeout.compareTo(requestTimeout) > 0) {
            throw new IllegalStateException("AI_CALL_TIMEOUT_MS must be <= AI_REQUEST_TIMEOUT_MS");
        }
        int retries = config.getInt("AI_TRANSPORT_RETRIES", "ai.transport.retries", 2);
        if (retries < 0) {
            throw new IllegalStateException("AI_TRANSPORT_RETRIES must be >= 0, got " + retries);
        }
        double temperature;
        String rawTemperature = config.get("AI_TEMPERATURE", "ai.temperature", "0.4");
        try {
            temperature = Double.parseDouble(rawTemperature.strip());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("AI_TEMPERATURE must be a number, got " + rawTemperature);
        }
        if (temperature < 0 || temperature > 2) {
            throw new IllegalStateException("AI_TEMPERATURE must be in 0..2, got " + temperature);
        }
        return new GeminiSettings(apiKey, model, baseUrl,
                Duration.ofMillis(positive(config, "AI_CONNECT_TIMEOUT_MS", "ai.connect.timeout.ms", 5_000)),
                callTimeout, retries, GeminiSettings.DEFAULT_BACKOFF,
                positive(config, "AI_MAX_OUTPUT_TOKENS", "ai.max.output.tokens", 8192),
                temperature,
                positive(config, "AI_RESPONSE_MAX_BYTES", "ai.response.max.bytes", 65_536));
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
        return "AiConfig{provider=" + provider + ", " + (gemini != null ? gemini + ", " : "") + limits + "}";
    }
}
