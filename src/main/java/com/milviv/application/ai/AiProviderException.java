package com.milviv.application.ai;

import java.util.List;

/**
 * Сбой провайдера (AiFailure, §11). Адаптер различает виды — HandleAiRequest решает, что с ними делать.
 * Не AppException: наружу не уходит, HandleAiRequest переводит её в код ошибки запроса.
 */
public class AiProviderException extends RuntimeException {

    public enum Kind {
        /** Сеть, 5xx, ошибка запроса к провайдеру, провайдер не настроен. */
        UNAVAILABLE,
        /** Кратковременный лимит провайдера (429), ретраи не помогли. */
        RATE_LIMITED,
        /** Квота провайдера исчерпана до сброса (429 суточный) — повторять бессмысленно. */
        QUOTA_EXHAUSTED,
        TIMEOUT,
        /** Ответ оборвался на пределе выходных токенов. */
        OUTPUT_TRUNCATED,
        /** Провайдер заблокировал запрос или ответ по своим правилам безопасности. */
        REFUSED,
        /** Ответ не разобран по контракту — считается неверным ходом. */
        MALFORMED
    }

    private final Kind kind;
    private final List<String> violations;

    public AiProviderException(Kind kind, String message) {
        this(kind, message, List.of());
    }

    /** @param violations для MALFORMED — замечания «путь: код», они уходят AI в повторный вызов */
    public AiProviderException(Kind kind, String message, List<String> violations) {
        super(message);
        this.kind = kind;
        this.violations = List.copyOf(violations);
    }

    public Kind getKind() {
        return kind;
    }

    public List<String> getViolations() {
        return violations;
    }
}
