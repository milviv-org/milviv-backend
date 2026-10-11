package com.milviv.domain.exception;

import com.milviv.domain.ai.RefusalReason;

/**
 * Ошибки AI-запроса (§11). Код хранится в ai_requests.error_code, поэтому повтор
 * завершённого с ошибкой запроса восстанавливает ту же ошибку.
 */
public class AiRequestException extends DomainException {

    public enum Code {
        AI_REQUEST_IN_PROGRESS("Предыдущий запрос ещё обрабатывается"),
        IDEMPOTENCY_KEY_REUSED("Ключ запроса уже использован с другим сообщением"),
        AI_PROVIDER_UNAVAILABLE("AI временно недоступен"),
        AI_TIMEOUT("AI не ответил вовремя"),
        AI_INVALID_RESPONSE("Не удалось обработать запрос, попробуйте переформулировать"),
        AI_REFUSED("AI не может выполнить этот запрос"),
        AI_PLAN_TOO_LARGE("Слишком много изменений за раз — уточните запрос"),
        BUSINESS_RULE_VIOLATION("Предложение AI нарушает правила. Ничего не изменено");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }

    private final Code code;

    public AiRequestException(Code code) {
        this(code, code.message);
    }

    private AiRequestException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public static AiRequestException refused(RefusalReason reason) {
        return new AiRequestException(Code.AI_REFUSED, switch (reason) {
            case OFF_TOPIC -> "Запрос не относится к целям и задачам";
            case UNSUPPORTED_OPERATION -> "Эта операция недоступна для AI";
            case OTHER -> Code.AI_REFUSED.message;
        });
    }

    public Code getCode() {
        return code;
    }

    @Override
    public String getErrorCode() {
        return code.name();
    }
}
