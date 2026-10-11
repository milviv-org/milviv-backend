package com.milviv.application.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiConsentRepository;
import com.milviv.domain.exception.AiConsentRequiredException;

/**
 * Согласие на использование данных для AI (G-12, §6.10).
 * requireConsent — единственная точка проверки: её вызывает каждый AI-сценарий
 * до обращения к провайдеру (инвариант 5).
 */
public class AiConsentService {

    private final AiConsentRepository consents;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public AiConsentService(AiConsentRepository consents, TransactionManager transactionManager, Clock clock) {
        this.consents = consents;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    /** @param acceptedAt null — согласия нет */
    public record ConsentView(boolean accepted, Instant acceptedAt) {}

    /** @param created true — согласие записано этим вызовом */
    public record AcceptResult(ConsentView consent, boolean created) {}

    public ConsentView status(UUID userId) {
        return transactionManager.execute(Propagation.SUPPORTS, () -> consents.findAcceptedAt(userId))
                .map(at -> new ConsentView(true, at))
                .orElse(new ConsentView(false, null));
    }

    /** Повтор ничего не меняет и возвращает исходную дату. */
    public AcceptResult accept(UUID userId) {
        Instant now = clock.instant();
        return transactionManager.execute(() -> {
            boolean created = consents.accept(userId, now);
            Instant acceptedAt = created ? now : consents.findAcceptedAt(userId).orElseThrow();
            return new AcceptResult(new ConsentView(true, acceptedAt), created);
        });
    }

    /** @throws AiConsentRequiredException согласия нет */
    public void requireConsent(UUID userId) {
        if (!status(userId).accepted()) {
            throw new AiConsentRequiredException();
        }
    }
}
