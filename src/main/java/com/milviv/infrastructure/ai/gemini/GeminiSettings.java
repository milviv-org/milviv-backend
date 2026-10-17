package com.milviv.infrastructure.ai.gemini;

import java.net.URI;
import java.time.Duration;

/**
 * Настройки Gemini из AiConfig (§8). URL провайдера — только отсюда (T-16).
 *
 * @param backoff пауза перед первым транспортным ретраем; дальше удваивается, плюс случайная добавка
 */
public record GeminiSettings(
        String apiKey,
        String model,
        URI baseUrl,
        Duration connectTimeout,
        Duration callTimeout,
        int retries,
        Duration backoff,
        int maxOutputTokens,
        double temperature,
        int responseMaxBytes
) {

    public static final Duration DEFAULT_BACKOFF = Duration.ofMillis(500);

    /** Ключ не попадает в логи (T-15). */
    @Override
    public String toString() {
        return "GeminiSettings{model=" + model + ", baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", callTimeout=" + callTimeout + ", retries=" + retries + ", maxOutputTokens=" + maxOutputTokens
                + ", temperature=" + temperature + ", responseMaxBytes=" + responseMaxBytes + ", apiKey=***}";
    }
}
