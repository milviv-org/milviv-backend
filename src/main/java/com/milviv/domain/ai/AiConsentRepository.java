package com.milviv.domain.ai;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Согласие на использование данных для AI (G-12). Даётся один раз, не отзывается (G-24),
 * хранится бессрочно как доказательство.
 */
public interface AiConsentRepository {

    /** Пусто — согласия нет. */
    Optional<Instant> findAcceptedAt(UUID userId);

    /**
     * Записать согласие, если его ещё нет.
     *
     * @return true — записано сейчас; false — уже было (дата не меняется)
     */
    boolean accept(UUID userId, Instant at);
}
