package com.milviv.domain.ai;

import java.util.List;
import java.util.UUID;

/** Память AI. Каждый метод фильтрует по userId (AD-14): чужая запись — как несуществующая. */
public interface AiMemoryRepository {

    /** Блокирует строку пользователя до конца транзакции: проверка лимита и запись идут по очереди. */
    void lockUser(UUID userId);

    void add(AiMemory memory);

    /** Новые первыми. */
    List<AiMemory> findByUser(UUID userId, int limit);

    int count(UUID userId);

    /** @return false — записи нет или она чужая */
    boolean delete(UUID userId, UUID memoryId);
}
