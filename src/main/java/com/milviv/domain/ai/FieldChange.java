package com.milviv.domain.ai;

/**
 * Изменение одного поля в плане AI (§6.5): само поле null — не менять,
 * FieldChange(x) — установить x, FieldChange(null) — очистить.
 */
public record FieldChange<T>(T value) {}
