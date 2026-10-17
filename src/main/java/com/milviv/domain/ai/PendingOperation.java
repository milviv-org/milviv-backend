package com.milviv.domain.ai;

import java.util.UUID;

import com.milviv.domain.ai.OperationReport.EntityType;

/**
 * Изменение или удаление, которое ждёт согласия пользователя (G-3).
 *
 * @param itemId          u1, u2… для изменений, d1, d2… для удалений
 * @param expectedVersion версия сущности при чтении AI: изменилась — TARGET_CHANGED (§10.1)
 * @param title           название из БД на момент предложения, не из текста AI (§6.6)
 * @param changes         для удаления null
 */
public record PendingOperation(String itemId, Op op, EntityType type, UUID targetId, long expectedVersion,
                               String title, EntityChanges changes) {

    public enum Op { UPDATE, DELETE }
}
