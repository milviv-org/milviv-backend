package com.milviv.domain.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * Вывод, который пользователь сам сохранил в памяти AI (пользовательская дока, AI Memory):
 * долгосрочный контекст для будущих запросов. Не история диалога и не статистика.
 */
public record AiMemory(UUID id, UUID userId, String content, Instant createdAt) {}
