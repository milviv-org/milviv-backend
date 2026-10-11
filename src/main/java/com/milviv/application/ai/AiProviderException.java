package com.milviv.application.ai;

/**
 * Сбой провайдера. Полная модель AiFailure (§11) — вместе с адаптером Gemini.
 * Не AppException: наружу не уходит, HandleAiRequest переводит её в код ошибки запроса.
 */
public class AiProviderException extends RuntimeException {

    public enum Kind {
        /** Сеть, 5xx, провайдер не настроен. */
        UNAVAILABLE,
        TIMEOUT,
        /** Ответ не разобран по контракту — считается неверным ходом. */
        MALFORMED
    }

    private final Kind kind;

    public AiProviderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
