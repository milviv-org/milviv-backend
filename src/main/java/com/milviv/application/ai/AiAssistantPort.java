package com.milviv.application.ai;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.DataRound;

/**
 * Провайдер AI (принцип 9): всё провайдерское — за этим интерфейсом.
 * Вызывается только вне транзакции (AD-08).
 */
public interface AiAssistantPort {

    /** Пишется в ai_requests.provider. */
    String provider();

    /** Пишется в ai_requests.model. */
    String model();

    /** @throws AiProviderException провайдер не ответил или ответ не разобран */
    AiReply nextTurn(AiPrompt prompt);

    /**
     * Что уходит AI (§6.1). Каталоги сложностей и запросов данных адаптер берёт из Difficulty и DataQuery.
     *
     * @param limits     лимиты плана — подсказка модели; соблюдение проверяет сервер
     * @param rounds     все раунды данных этого запроса по порядку: что AI просил и что получил
     * @param call       номер вызова в этом запросе, с 1
     * @param violations замечания к прошлому ходу — AI должен их исправить; пусто — первый ход
     * @param timeLeft   сколько осталось до таймаута всего запроса
     */
    record AiPrompt(String contractVersion, String promptVersion, String locale, String timezone, LocalDate today,
                    String userMessage, int call, List<String> violations, Duration timeLeft,
                    AiLimits.Plan limits, List<DataRound> rounds) {}

    /** Токены null — провайдер их не сообщил. */
    record AiReply(AiTurn turn, Integer inputTokens, Integer outputTokens) {}
}
