package com.milviv.domain.ai;

/**
 * Запись журнала прочитанного (§6.3, §13): что AI прочитал, без содержимого.
 *
 * @param reason зачем AI просил данные — показывается пользователю
 */
public record DataAccess(String query, int items, String reason) {}
