package com.milviv.infrastructure.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.milviv.application.ai.AiContract;
import com.milviv.application.ai.AiProviderException;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.DataQuery;
import com.milviv.domain.ai.RefusalReason;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.task.TaskStatus;
import com.milviv.infrastructure.ai.AssistantV1Json.Query;
import com.milviv.infrastructure.ai.AssistantV1Json.Turn;

/**
 * Строгий разбор текста модели в AiTurn (§6.2, принцип 6). Схема у провайдера — лишь подсказка,
 * поэтому проверяется всё:
 * - неизвестное поле, дубликат ключа, лишний текст после JSON (в том числе обёртка ```) — отказ;
 * - типы без приведения: "5" не станет 5, число не станет enum;
 * - поле не своего outcome и параметр, не предусмотренный для запроса данных, — отказ.
 *
 * Отказ — MALFORMED с замечаниями «путь: код»: они уходят AI в повторный вызов.
 * Нормализация текстов и проверка значений — дальше, в AiTurnValidator.
 */
public class AiTurnParser {

    /** Какие поля хода допустимы у каждого исхода; contractVersion и outcome — у всех. */
    private static final Map<String, Set<String>> OUTCOME_FIELDS = Map.of(
            "DATA_REQUEST", Set.of("dataRequestReason", "dataRequests"),
            "PROPOSAL", Set.of("summary", "answer", "plan"),
            "ANSWER", Set.of("answer"),
            "CLARIFICATION_NEEDED", Set.of("clarificationQuestion"),
            "REFUSED", Set.of("refusalReason"));

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** @throws AiProviderException MALFORMED с замечаниями */
    public AiTurn parse(String text) {
        if (text == null || text.isBlank()) {
            throw malformed(List.of("response: EMPTY"));
        }
        Turn turn = read(text);
        List<String> violations = new ArrayList<>();

        if (turn.contractVersion() == null) {
            violations.add("contractVersion: REQUIRED");
        } else if (!AiContract.CONTRACT_VERSION.equals(turn.contractVersion())) {
            violations.add("contractVersion: INVALID_VALUE");
        }
        Set<String> allowed = turn.outcome() == null ? null : OUTCOME_FIELDS.get(turn.outcome());
        if (turn.outcome() == null) {
            violations.add("outcome: REQUIRED");
        } else if (allowed == null) {
            violations.add("outcome: INVALID_VALUE");
        } else {
            present(turn).forEach(field -> {
                if (!allowed.contains(field)) {
                    violations.add(field + ": NOT_ALLOWED_FOR_OUTCOME");
                }
            });
        }
        if (!violations.isEmpty()) {
            throw malformed(violations);
        }

        AiTurn result = switch (turn.outcome()) {
            case "ANSWER" -> new AiTurn.Answer(turn.answer());
            case "CLARIFICATION_NEEDED" -> new AiTurn.ClarificationNeeded(turn.clarificationQuestion());
            case "PROPOSAL" -> new AiTurn.Proposal(turn.summary(), turn.answer(), turn.plan());
            case "REFUSED" -> new AiTurn.Refused(refusalReason(turn.refusalReason(), violations));
            case "DATA_REQUEST" -> new AiTurn.DataRequest(turn.dataRequestReason(), queries(turn.dataRequests(), violations));
            default -> throw new IllegalStateException("Unchecked outcome " + turn.outcome());
        };
        if (!violations.isEmpty()) {
            throw malformed(violations);
        }
        return result;
    }

    private Turn read(String text) {
        try {
            return mapper.readValue(text, Turn.class);
        } catch (UnrecognizedPropertyException ex) {
            throw malformed(List.of(path(ex) + ": UNKNOWN_FIELD"));
        } catch (JsonMappingException ex) {
            // тип не тот: строка вместо числа, неизвестное значение enum, не объект
            String at = path(ex);
            throw malformed(List.of((at.isEmpty() ? "response" : at) + ": INVALID_VALUE"));
        } catch (JsonParseException ex) {
            boolean duplicate = ex.getOriginalMessage() != null && ex.getOriginalMessage().startsWith("Duplicate field");
            throw malformed(List.of(duplicate ? "response: DUPLICATE_KEY" : "response: NOT_JSON"));
        } catch (JsonProcessingException ex) {
            throw malformed(List.of("response: NOT_JSON"));
        }
    }

    /** Путь в стиле замечаний AiPlanPolicy: plan.update.tasks[0].set.status. */
    private static String path(JsonMappingException ex) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : ex.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static List<String> present(Turn turn) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("dataRequestReason", turn.dataRequestReason());
        fields.put("dataRequests", turn.dataRequests());
        fields.put("summary", turn.summary());
        fields.put("answer", turn.answer());
        fields.put("plan", turn.plan());
        fields.put("clarificationQuestion", turn.clarificationQuestion());
        fields.put("refusalReason", turn.refusalReason());
        return fields.entrySet().stream().filter(e -> e.getValue() != null).map(Map.Entry::getKey).toList();
    }

    private static RefusalReason refusalReason(String value, List<String> violations) {
        if (value == null) {
            return null; // REQUIRED проверит AiTurnValidator
        }
        try {
            return RefusalReason.valueOf(value);
        } catch (IllegalArgumentException ex) {
            violations.add("refusalReason: INVALID_VALUE");
            return null;
        }
    }

    /** null внутри списка остаётся null — его отклонит AiTurnValidator (NULL_ITEM). */
    private static List<DataQuery> queries(List<Query> queries, List<String> violations) {
        if (queries == null) {
            return null;
        }
        List<DataQuery> result = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            Query q = queries.get(i);
            result.add(q == null ? null : query(q, "dataRequests[" + i + "]", violations));
        }
        return result;
    }

    private static DataQuery query(Query q, String at, List<String> violations) {
        List<String> allowed = q.query() == null ? null : AssistantV1Json.QUERY_PARAMS.get(q.query());
        if (allowed == null) {
            violations.add(at + ".query: " + (q.query() == null ? "REQUIRED" : "INVALID_VALUE"));
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("goal", q.goal());
        params.put("project", q.project());
        params.put("task", q.task());
        params.put("unassigned", q.unassigned());
        params.put("status", q.status());
        params.put("period", q.period());
        params.forEach((name, value) -> {
            if (value != null && !allowed.contains(name)) {
                violations.add(at + "." + name + ": NOT_ALLOWED");
            }
        });
        DataQuery.Period period = q.period() == null ? null : new DataQuery.Period(q.period().from(), q.period().to());
        return switch (q.query()) {
            case "LIST_GOALS" -> new DataQuery.ListGoals();
            case "LIST_PROJECTS" -> new DataQuery.ListProjects(q.goal(), status(ProjectStatus.class, q.status(), at, violations));
            case "LIST_TASKS" -> new DataQuery.ListTasks(q.project(), q.goal(), q.unassigned(),
                    status(TaskStatus.class, q.status(), at, violations), period);
            case "LIST_REWARDS" -> new DataQuery.ListRewards();
            case "LIST_PLANS" -> new DataQuery.ListPlans(period);
            case "LIST_REFLECTIONS" -> new DataQuery.ListReflections(q.task(), period);
            default -> throw new IllegalStateException("Unchecked query " + q.query());
        };
    }

    private static <E extends Enum<E>> E status(Class<E> type, String value, String at, List<String> violations) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            violations.add(at + ".status: INVALID_VALUE");
            return null;
        }
    }

    private static AiProviderException malformed(List<String> violations) {
        return new AiProviderException(AiProviderException.Kind.MALFORMED, "AI response does not match "
                + AiContract.CONTRACT_VERSION, violations);
    }
}
