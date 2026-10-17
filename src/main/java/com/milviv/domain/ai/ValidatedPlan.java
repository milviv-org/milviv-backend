package com.milviv.domain.ai;

import java.util.List;

/**
 * Проверенный план, сохранённый до решения пользователя (§4.3): только ожидающие операции —
 * создания к этому моменту уже исполнены.
 *
 * @param format версия формата: план другой версии после деплоя считается просроченным (§14)
 */
public record ValidatedPlan(int format, List<PendingOperation> operations) {

    public static final int FORMAT = 1;

    public ValidatedPlan {
        operations = List.copyOf(operations);
    }

    public static ValidatedPlan of(List<PendingOperation> operations) {
        return new ValidatedPlan(FORMAT, operations);
    }
}
