package com.milviv.infrastructure.ai;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.milviv.application.ai.contract.PlanDraft;

/**
 * Проводной формат ответа AI, assistant.v1 (§6.2): во что разбирается текст модели до перевода в AiTurn.
 *
 * Ход плоский: outcome + поля всех исходов, у чужого исхода они null — так проще схема для Gemini
 * (без oneOf). Соответствие полей исходу проверяет AiTurnParser. План — те же records, что PlanDraft.
 * Схема ответа — resources/ai/assistant.v1.schema.json; совпадение полей проверяет контрактный тест.
 */
public final class AssistantV1Json {

    private AssistantV1Json() {}

    /** Каталог запросов данных v1 (§6.3): запрос → допустимые параметры, остальные должны быть null. */
    public static final Map<String, List<String>> QUERY_PARAMS;

    static {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("LIST_GOALS", List.of());
        params.put("LIST_PROJECTS", List.of("goal", "status"));
        params.put("LIST_TASKS", List.of("project", "goal", "unassigned", "status", "period"));
        params.put("LIST_REWARDS", List.of());
        params.put("LIST_PLANS", List.of("period"));
        params.put("LIST_REFLECTIONS", List.of("task", "period"));
        QUERY_PARAMS = Collections.unmodifiableMap(params);
    }

    public record Turn(
            String contractVersion,
            String outcome,
            String dataRequestReason,
            List<Query> dataRequests,
            String summary,
            String answer,
            PlanDraft plan,
            String clarificationQuestion,
            String refusalReason
    ) {}

    /** Параметр, не предусмотренный для query, должен быть null (§6.3). */
    public record Query(String query, String goal, String project, String task, Boolean unassigned, String status,
                        Period period) {}

    public record Period(LocalDate from, LocalDate to) {}
}
