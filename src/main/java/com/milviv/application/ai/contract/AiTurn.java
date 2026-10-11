package com.milviv.application.ai.contract;

import java.util.List;

import com.milviv.domain.ai.RefusalReason;

/**
 * Ход AI по контракту assistant.v1 (§6.2). НЕДОВЕРЕННЫЕ данные: перед использованием —
 * только через AiTurnValidator. Провайдерские типы сюда не попадают (инвариант 9).
 *
 * DataRequest — просьба, не команда: сервер решает, что отдать (принцип 1).
 */
public sealed interface AiTurn {

    /** Текстовый ответ, ничего не исполняется. */
    record Answer(String answer) implements AiTurn {}

    /** Вопрос пользователю; истории диалога нет — клиент дополняет исходный текст. */
    record ClarificationNeeded(String clarificationQuestion) implements AiTurn {}

    record Refused(RefusalReason refusalReason) implements AiTurn {}

    /**
     * Предложение операций (§6.5).
     *
     * @param summary пояснение к операциям для пользователя
     * @param answer  текст рядом с операциями, например результат анализа (G-21); может быть null
     */
    record Proposal(String summary, String answer, PlanDraft plan) implements AiTurn {}

    /**
     * Явный запрос данных из каталога (G-7, §6.3).
     *
     * @param dataRequestReason зачем — показывается пользователю
     */
    record DataRequest(String dataRequestReason, List<DataQuery> dataRequests) implements AiTurn {}
}
