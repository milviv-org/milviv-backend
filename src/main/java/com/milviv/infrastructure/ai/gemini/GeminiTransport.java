package com.milviv.infrastructure.ai.gemini;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.milviv.application.ai.AiProviderException;
import com.milviv.application.ai.AiProviderException.Kind;

/**
 * HTTP к generateContent (JDK HttpClient). Отвечает за то, что не зависит от смысла ответа:
 * - таймаут вызова — меньшее из AI_CALL_TIMEOUT_MS и времени, оставшегося у запроса;
 * - транспортные ретраи (≤ AI_TRANSPORT_RETRIES): 5xx, сеть, таймаут, кратковременный 429 (§8);
 *   пауза — из RetryInfo провайдера или backoff с удвоением и jitter; ретрай не начинается,
 *   если пауза не влезает в оставшееся время;
 * - предел байт ответа — до разбора (T-12);
 * - 429: суточная квота — QUOTA_EXHAUSTED без ретрая, остальное — RATE_LIMITED.
 *
 * Ключ — в заголовке, не в URL: URL попадает в логи прокси.
 */
public class GeminiTransport {

    private static final Logger logger = LoggerFactory.getLogger(GeminiTransport.class);

    private final GeminiSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;

    public GeminiTransport(GeminiSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
        String base = settings.baseUrl().toString().replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/v1beta/models/" + settings.model() + ":generateContent");
    }

    /** Итог одной попытки. */
    private sealed interface Attempt {
        record Success(String body) implements Attempt {}

        /** @param retryAfter пауза, которую назвал провайдер; null — своя */
        record Failure(AiProviderException error, boolean retryable, Duration retryAfter) implements Attempt {}
    }

    /**
     * @param timeLeft сколько осталось до таймаута всего AI-запроса
     * @return тело ответа 200
     * @throws AiProviderException последний сбой, если ретраи не помогли
     */
    public String generate(String requestBody, Duration timeLeft) {
        long deadline = System.nanoTime() + timeLeft.toNanos();
        Attempt.Failure last = null;
        for (int attempt = 0; attempt <= settings.retries(); attempt++) {
            if (last != null) {
                Duration pause = pause(attempt, last.retryAfter());
                if (pause.compareTo(remaining(deadline)) >= 0) {
                    break;
                }
                sleep(pause);
            }
            Duration remaining = remaining(deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                break;
            }
            Duration timeout = remaining.compareTo(settings.callTimeout()) < 0 ? remaining : settings.callTimeout();
            Attempt result = send(requestBody, timeout);
            if (result instanceof Attempt.Success success) {
                return success.body();
            }
            last = (Attempt.Failure) result;
            logger.warn("Gemini attempt {} failed: {}", attempt + 1, last.error().getKind());
            if (!last.retryable()) {
                break;
            }
        }
        throw last != null ? last.error() : new AiProviderException(Kind.TIMEOUT, "No time left for AI call");
    }

    private Attempt send(String requestBody, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes(settings.responseMaxBytes() + 1);
            }
            if (bytes.length > settings.responseMaxBytes()) {
                return new Attempt.Failure(new AiProviderException(Kind.MALFORMED, "Gemini response is too large",
                        List.of("response: TOO_LARGE")), false, null);
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            int status = response.statusCode();
            if (status == 200) {
                return new Attempt.Success(body);
            }
            if (status == 429) {
                return tooManyRequests(body);
            }
            if (status >= 500) {
                return new Attempt.Failure(new AiProviderException(Kind.UNAVAILABLE, "Gemini HTTP " + status), true, null);
            }
            // 400, 401, 403, 404: ошибка конфигурации или запроса — повтор не поможет
            logger.error("Gemini HTTP {}: {}", status, errorMessage(body));
            return new Attempt.Failure(new AiProviderException(Kind.UNAVAILABLE, "Gemini HTTP " + status), false, null);
        } catch (HttpTimeoutException ex) {
            return new Attempt.Failure(new AiProviderException(Kind.TIMEOUT, "Gemini call timed out"), true, null);
        } catch (IOException ex) {
            return new Attempt.Failure(new AiProviderException(Kind.UNAVAILABLE, "Gemini is unreachable: "
                    + ex.getClass().getSimpleName()), true, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Attempt.Failure(new AiProviderException(Kind.UNAVAILABLE, "Interrupted"), false, null);
        }
    }

    /**
     * 429 RESOURCE_EXHAUSTED. Суточная квота узнаётся по QuotaFailure.violations[].quotaId
     * (…PerDay…) — тогда до сброса повторять бессмысленно. Формат проверяется онлайн-прогоном.
     */
    private Attempt tooManyRequests(String body) {
        boolean daily = false;
        Duration retryAfter = null;
        try {
            for (JsonNode detail : mapper.readTree(body).path("error").path("details")) {
                String type = detail.path("@type").asText("");
                if (type.endsWith("QuotaFailure")) {
                    for (JsonNode violation : detail.path("violations")) {
                        daily |= violation.path("quotaId").asText("").contains("PerDay");
                    }
                } else if (type.endsWith("RetryInfo")) {
                    retryAfter = parseDelay(detail.path("retryDelay").asText(""));
                }
            }
        } catch (IOException ex) {
            // тело не JSON — считаем кратковременным лимитом
        }
        if (daily) {
            logger.warn("Gemini quota exhausted until reset");
            return new Attempt.Failure(new AiProviderException(Kind.QUOTA_EXHAUSTED, "Gemini quota exhausted"), false, null);
        }
        return new Attempt.Failure(new AiProviderException(Kind.RATE_LIMITED, "Gemini rate limit"), true, retryAfter);
    }

    /** «37s», «1.5s» → Duration; не разобрано — null. */
    static Duration parseDelay(String value) {
        if (!value.endsWith("s")) {
            return null;
        }
        try {
            return Duration.ofMillis(Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 1000));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String errorMessage(String body) {
        try {
            return mapper.readTree(body).path("error").path("message").asText("(no message)");
        } catch (IOException ex) {
            return "(not JSON)";
        }
    }

    private Duration pause(int attempt, Duration retryAfter) {
        if (retryAfter != null) {
            return retryAfter;
        }
        long base = settings.backoff().toMillis() << (attempt - 1);
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(settings.backoff().toMillis() + 1));
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(deadline - System.nanoTime());
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
