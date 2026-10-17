package com.milviv.domain.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** AI-запросы. Каждый метод фильтрует по userId (AD-14): чужой запрос — как несуществующий. */
public interface AiRequestRepository {

    /**
     * Блокирует строку пользователя до конца транзакции: начало запросов одного пользователя
     * идёт по очереди, поэтому проверки «ключ уже был» и «есть активный» не гонятся друг с другом.
     */
    void lockUser(UUID userId);

    void add(AiRequest request);

    Optional<AiRequest> findById(UUID userId, UUID requestId);

    /** С блокировкой строки до конца транзакции: два решения по одной карточке идут по очереди. */
    Optional<AiRequest> findByIdForUpdate(UUID userId, UUID requestId);

    Optional<AiRequest> findByIdempotencyKey(UUID userId, String idempotencyKey);

    /** Запрос в PENDING_AI — у пользователя он не больше одного. */
    Optional<AiRequest> findPending(UUID userId);

    /** Новые первыми. */
    List<AiRequest> findByUser(UUID userId, int limit, int offset);

    /** Когда начаты запросы пользователя с момента since — для квот; старые первыми. */
    List<Instant> findStartTimesSince(UUID userId, Instant since);

    /** Сколько создано запросом, начатым в startedAt. */
    record Creations(Instant startedAt, int count) {}

    /** Созданное через AI в запросах, начатых с момента since (по CREATED в отчётах); старые первыми. */
    List<Creations> findCreationsSince(UUID userId, Instant since);

    /** Версия не совпала — OptimisticLockException. */
    void update(AiRequest request);
}
