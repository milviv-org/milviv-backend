package com.milviv.domain.ai;

import java.time.LocalDate;
import java.util.UUID;

import com.milviv.domain.task.Difficulty;

/**
 * Проверенные изменения одной сущности: дескрипторы и ref уже заменены на ID.
 * Поле null — не меняется. Какие поля допустимы для типа, решил AiPlanPolicy (белый список §6.5).
 */
public record EntityChanges(
        FieldChange<String> title,
        FieldChange<String> description,
        FieldChange<Difficulty> difficulty,
        FieldChange<Integer> points,
        FieldChange<LocalDate> startDate,
        FieldChange<LocalDate> deadline,
        FieldChange<UUID> projectId,
        FieldChange<UUID> goalId,
        FieldChange<Integer> cost
) {

    /** Значение поля после изменения: не меняется — текущее. */
    public static <T> T apply(FieldChange<T> change, T current) {
        return change == null ? current : change.value();
    }
}
