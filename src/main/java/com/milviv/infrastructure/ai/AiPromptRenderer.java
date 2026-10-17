package com.milviv.infrastructure.ai;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.milviv.application.ai.AiAssistantPort.AiPrompt;
import com.milviv.application.ai.AiLimits;
import com.milviv.application.ai.contract.DataRound;
import com.milviv.domain.task.Difficulty;

/**
 * Ход для AI (§6.1) — один JSON-документ на каждый вызов, без истории модели:
 * контекст, лимиты, каталог, сообщение пользователя, все раунды данных, замечания к прошлому ходу.
 *
 * Сообщение пользователя и данные — значения JSON, а не текст между тегами: экранирование не даёт
 * им «закрыть тег» и выдать себя за инструкцию (T-10). Что это данные, а не команды, говорит инструкция.
 */
public class AiPromptRenderer {

    record Document(String contractVersion, String promptVersion, String locale, String timezone, LocalDate today,
                    int call, int maxDataRounds, int dataRoundsLeft, AiLimits.Plan limits, Catalog catalog,
                    String userMessage, List<Round> dataRounds, List<String> violations) {}

    record Catalog(List<DifficultyPoints> difficulty, List<QuerySpec> dataQueries, List<String> operations) {}

    record DifficultyPoints(Difficulty value, int points) {}

    /** @param params параметры, которые допустимы у запроса; остальные — null */
    record QuerySpec(String query, List<String> params) {}

    record Round(int round, String reason, List<DataRound.DataResult> dataResults,
                 List<DataRound.RejectedRequest> rejectedRequests) {}

    /** Каталог запросов — тот же, что проверяет AiTurnParser. */
    private static final Catalog CATALOG = new Catalog(
            Stream.of(Difficulty.values()).map(d -> new DifficultyPoints(d, d.defaultPoints())).toList(),
            AssistantV1Json.QUERY_PARAMS.entrySet().stream().map(e -> new QuerySpec(e.getKey(), e.getValue())).toList(),
            List.of("CREATE", "UPDATE", "DELETE"));

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // пустые поля проекций не тратят токены
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private final int maxDataRounds;

    public AiPromptRenderer(int maxDataRounds) {
        this.maxDataRounds = maxDataRounds;
    }

    public String render(AiPrompt prompt) {
        List<Round> rounds = prompt.rounds().stream()
                .map(r -> new Round(r.round(), r.reason(), r.results(), r.rejected()))
                .toList();
        Document document = new Document(prompt.contractVersion(), prompt.promptVersion(), prompt.locale(),
                prompt.timezone(), prompt.today(), prompt.call(), maxDataRounds,
                Math.max(0, maxDataRounds - rounds.size()), prompt.limits(), CATALOG, prompt.userMessage(), rounds,
                prompt.violations());
        try {
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to render AI prompt", ex);
        }
    }
}
