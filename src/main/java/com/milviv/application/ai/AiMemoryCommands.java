package com.milviv.application.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiMemory;
import com.milviv.domain.ai.AiMemoryRepository;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;

/**
 * Память AI: выводы, которые пользователь сам решил сохранить (AI Memory). AI их не пишет —
 * только читает в каждом запросе (HandleAiRequest).
 *
 * Без согласия недоступна: память уходит провайдеру вместе с запросами (§6.10).
 */
public class AiMemoryCommands {

    private final AiConsentService consentService;
    private final AiMemoryRepository memories;
    private final AiLimits.Memory limits;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public AiMemoryCommands(AiConsentService consentService, AiMemoryRepository memories, AiLimits limits,
                            TransactionManager transactionManager, Clock clock) {
        this.consentService = consentService;
        this.memories = memories;
        this.limits = limits.memory();
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public record AiMemoryView(UUID id, String content, Instant createdAt) {

        static AiMemoryView from(AiMemory memory) {
            return new AiMemoryView(memory.id(), memory.content(), memory.createdAt());
        }
    }

    public List<AiMemoryView> list(UUID userId) {
        consentService.requireConsent(userId);
        return transactionManager.execute(Propagation.SUPPORTS, () -> memories.findByUser(userId, limits.maxItems()))
                .stream()
                .map(AiMemoryView::from)
                .toList();
    }

    /** @throws AiRequestException AI_MEMORY_FULL — записей уже maxItems */
    public AiMemoryView create(UUID userId, String rawContent) {
        consentService.requireConsent(userId);
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty()) {
            throw new ValidationException("content", "Текст не может быть пустым");
        }
        if (content.length() > limits.maxLength()) {
            throw new ValidationException("content", "Текст длиннее " + limits.maxLength() + " символов");
        }
        Instant now = clock.instant();
        return AiMemoryView.from(transactionManager.execute(() -> {
            // под блокировкой пользователя: две параллельные записи не превысят лимит
            memories.lockUser(userId);
            if (memories.count(userId) >= limits.maxItems()) {
                throw new AiRequestException(AiRequestException.Code.AI_MEMORY_FULL);
            }
            AiMemory memory = new AiMemory(UUID.randomUUID(), userId, content, now);
            memories.add(memory);
            return memory;
        }));
    }

    public void delete(UUID userId, UUID memoryId) {
        consentService.requireConsent(userId);
        boolean deleted = transactionManager.execute(() -> memories.delete(userId, memoryId));
        if (!deleted) {
            throw new NotFoundException("Запись памяти не найдена");
        }
    }
}
