package com.milviv.domain.exception;

/** Нет согласия на использование данных — AI недоступен (G-12). */
public class AiConsentRequiredException extends DomainException {

    public AiConsentRequiredException() {
        super("Чтобы использовать ИИ, дайте согласие на использование ваших данных");
    }

    @Override
    public String getErrorCode() {
        return "AI_CONSENT_REQUIRED";
    }
}
