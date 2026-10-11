package com.milviv.domain.ai;

import java.util.List;
import java.util.UUID;

/**
 * Отчёт об операциях AI-запроса (G-21, §6.7). Строится по фактическому результату
 * сценариев, а не по тексту AI (инвариант 6). Без деталей «было → стало».
 */
public record OperationReport(List<Item> items) {

    public enum Result { CREATED, UPDATED, DELETED, CANCELLED }

    public enum EntityType { GOAL, PROJECT, TASK, REWARD }

    /**
     * @param itemId путь операции в плане: g1, g1.p1, g1.p1.t1, p1, t1, r1
     * @param id     ID сущности; null — удалена или не создана
     */
    public record Item(String itemId, Result result, EntityType type, UUID id, String title) {}

    public OperationReport {
        items = List.copyOf(items);
    }
}
