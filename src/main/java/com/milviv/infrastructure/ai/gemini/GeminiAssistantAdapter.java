package com.milviv.infrastructure.ai.gemini;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import com.milviv.application.ai.AiAssistantPort;
import com.milviv.application.ai.AiContract;
import com.milviv.application.ai.AiLimits;
import com.milviv.application.ai.AiProviderException;
import com.milviv.application.ai.AiProviderException.Kind;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.infrastructure.ai.AiPromptRenderer;
import com.milviv.infrastructure.ai.AiTurnParser;

/**
 * Провайдер Gemini (принцип 9): всё, что знает о Gemini, — здесь и в GeminiTransport.
 *
 * Один вызов = один generateContent без истории модели: системная инструкция assistant@1,
 * ход в JSON (AiPromptRenderer), схема ответа assistant.v1 — подсказка, а не гарантия:
 * ответ разбирает AiTurnParser.
 *
 * Тексты промпта и ответа логируются только на DEBUG (T-15).
 */
public class GeminiAssistantAdapter implements AiAssistantPort {

    private static final Logger logger = LoggerFactory.getLogger(GeminiAssistantAdapter.class);

    static final String INSTRUCTION_RESOURCE = "/ai/prompts/" + AiContract.PROMPT_VERSION + ".md";
    static final String SCHEMA_RESOURCE = "/ai/" + AiContract.CONTRACT_VERSION + ".schema.json";

    /** Причины, по которым провайдер отказался отвечать по своим правилам. */
    private static final Set<String> BLOCKED = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII");

    record Request(Content systemInstruction, List<Content> contents, GenerationConfig generationConfig) {}

    record Content(String role, List<Part> parts) {}

    record Part(String text) {}

    /** Имя поля схемы — по текущей документации; подтверждается онлайн-прогоном. */
    record GenerationConfig(String responseMimeType, JsonNode responseJsonSchema, int maxOutputTokens,
                            double temperature) {}

    private final GeminiSettings settings;
    private final ObjectMapper mapper = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    private final GeminiTransport transport;
    private final AiPromptRenderer renderer;
    private final AiTurnParser parser = new AiTurnParser();
    private final String instruction;
    private final JsonNode schema;

    public GeminiAssistantAdapter(GeminiSettings settings, AiLimits limits) {
        this.settings = settings;
        this.transport = new GeminiTransport(settings, mapper);
        this.renderer = new AiPromptRenderer(limits.data().maxRounds());
        this.instruction = resource(INSTRUCTION_RESOURCE);
        try {
            this.schema = mapper.readTree(resource(SCHEMA_RESOURCE));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid " + SCHEMA_RESOURCE, ex);
        }
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public String model() {
        return settings.model();
    }

    @Override
    public AiReply nextTurn(AiPrompt prompt) {
        long started = System.nanoTime();
        String turnJson = renderer.render(prompt);
        logger.debug("Gemini call {} prompt: {}", prompt.call(), turnJson);
        String body = json(new Request(
                new Content(null, List.of(new Part(instruction))),
                List.of(new Content("user", List.of(new Part(turnJson)))),
                new GenerationConfig("application/json", schema, settings.maxOutputTokens(), settings.temperature())));

        JsonNode response = readResponse(transport.generate(body, prompt.timeLeft()));
        JsonNode usage = response.path("usageMetadata");
        Integer input = integer(usage.path("promptTokenCount"));
        Integer output = sum(integer(usage.path("candidatesTokenCount")), integer(usage.path("thoughtsTokenCount")));

        String blockReason = response.path("promptFeedback").path("blockReason").asText(null);
        JsonNode candidate = response.path("candidates").path(0);
        String finishReason = candidate.path("finishReason").asText(null);
        logger.info("Gemini call {} model={} finishReason={} blockReason={} inputTokens={} outputTokens={} latencyMs={}",
                prompt.call(), settings.model(), finishReason, blockReason, input, output,
                (System.nanoTime() - started) / 1_000_000);

        if (blockReason != null || (finishReason != null && BLOCKED.contains(finishReason))) {
            throw new AiProviderException(Kind.REFUSED, "Gemini blocked: " + (blockReason != null ? blockReason : finishReason));
        }
        if ("MAX_TOKENS".equals(finishReason)) {
            throw new AiProviderException(Kind.OUTPUT_TRUNCATED, "Gemini output truncated");
        }
        if (candidate.isMissingNode()) {
            throw malformed("response: NO_CANDIDATES");
        }
        if (finishReason != null && !"STOP".equals(finishReason)) {
            throw malformed("response: FINISH_" + finishReason);
        }

        String text = text(candidate);
        logger.debug("Gemini call {} answer: {}", prompt.call(), text);
        AiTurn turn = parser.parse(text);
        return new AiReply(turn, input, output);
    }

    /** Текст ответа без «размышлений» модели (части с thought=true). */
    private static String text(JsonNode candidate) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.has("text")) {
                sb.append(part.path("text").asText());
            }
        }
        return sb.toString();
    }

    private JsonNode readResponse(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw malformed("response: NOT_JSON");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to build Gemini request", ex);
        }
    }

    private static Integer integer(JsonNode node) {
        return node.isIntegralNumber() ? node.intValue() : null;
    }

    /** Без тернарного оператора: он распаковал бы null в int. */
    private static Integer sum(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }

    private static AiProviderException malformed(String violation) {
        return new AiProviderException(Kind.MALFORMED, "Unexpected Gemini response", List.of(violation));
    }

    private static String resource(String path) {
        try (InputStream in = GeminiAssistantAdapter.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
