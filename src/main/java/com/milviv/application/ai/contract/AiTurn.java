package com.milviv.application.ai.contract;

import com.milviv.domain.ai.RefusalReason;

/**
 * Ход AI по контракту assistant.v1 (§6.2). НЕДОВЕРЕННЫЕ данные: перед использованием —
 * только через AiTurnValidator. Провайдерские типы сюда не попадают (инвариант 9).
 *
 * В 9.3–9.4 добавятся Proposal и DataRequest.
 */
public sealed interface AiTurn {

    /** Текстовый ответ, ничего не исполняется. */
    record Answer(String answer) implements AiTurn {}

    /** Вопрос пользователю; истории диалога нет — клиент дополняет исходный текст. */
    record ClarificationNeeded(String clarificationQuestion) implements AiTurn {}

    record Refused(RefusalReason refusalReason) implements AiTurn {}
}
