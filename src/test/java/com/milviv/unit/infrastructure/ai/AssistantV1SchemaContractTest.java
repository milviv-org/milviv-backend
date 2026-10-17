package com.milviv.unit.infrastructure.ai;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.milviv.domain.ai.FieldChange;
import com.milviv.infrastructure.ai.AiTurnParser;
import com.milviv.infrastructure.ai.AssistantV1Json;

/**
 * Контракт офлайн (§12): схема ответа для Gemini ⇄ records проводного формата ⇄ фикстуры.
 * Схема — лишь подсказка модели, но если она разойдётся с records, модель будет получать
 * отказ за поля, которые ей предлагает сама схема.
 */
class AssistantV1SchemaContractTest {

    private static JsonNode schema() throws IOException {
        try (InputStream in = AssistantV1SchemaContractTest.class.getResourceAsStream("/ai/assistant.v1.schema.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void schemaProperties_matchRecordComponents_everywhere() throws IOException {
        List<String> mismatches = new ArrayList<>();
        compare("", schema(), AssistantV1Json.Turn.class, mismatches);
        assertThat(mismatches).isEmpty();
    }

    @Test
    void everyObject_forbidsExtraProperties() throws IOException {
        List<String> open = new ArrayList<>();
        closed("", schema(), open);
        assertThat(open).isEmpty();
    }

    @Test
    void everyFixture_parses() {
        AiTurnParser parser = new AiTurnParser();
        for (String name : List.of("answer.json", "clarification.json", "refused.json", "data-request.json",
                "proposal-create.json", "proposal-changes.json")) {
            assertThat(parser.parse(AiTurnParserTest.fixture(name))).as(name).isNotNull();
        }
    }

    /** Свойства объекта схемы = компоненты record; вглубь — по типам компонентов. */
    private static void compare(String path, JsonNode node, Class<?> type, List<String> mismatches) {
        Set<String> inSchema = new TreeSet<>();
        node.path("properties").fieldNames().forEachRemaining(inSchema::add);
        Set<String> inRecord = new TreeSet<>(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList());
        if (!inSchema.equals(inRecord)) {
            mismatches.add((path.isEmpty() ? "(root)" : path) + ": schema " + inSchema + " vs " + type.getSimpleName() + " " + inRecord);
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            JsonNode child = node.path("properties").path(component.getName());
            walk(path.isEmpty() ? component.getName() : path + "." + component.getName(), child,
                    component.getGenericType(), mismatches);
        }
    }

    private static void walk(String path, JsonNode node, Type type, List<String> mismatches) {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type argument = parameterized.getActualTypeArguments()[0];
            if (List.class.equals(raw)) {
                walk(path + "[]", node.path("items"), argument, mismatches);
            } else if (FieldChange.class.equals(raw)) {
                compare(path, node, FieldChange.class, mismatches);
                walk(path + ".value", node.path("properties").path("value"), argument, mismatches);
            }
        } else if (type instanceof Class<?> cls && cls.isRecord() && !FieldChange.class.equals(cls)) {
            compare(path, node, cls, mismatches);
        }
    }

    private static void closed(String path, JsonNode node, List<String> open) {
        if (node.has("properties")) {
            if (!node.path("additionalProperties").isBoolean() || node.path("additionalProperties").asBoolean()) {
                open.add(path.isEmpty() ? "(root)" : path);
            }
            node.path("properties").fields().forEachRemaining(e -> closed(path + "." + e.getKey(), e.getValue(), open));
        }
        if (node.has("items")) {
            closed(path + "[]", node.path("items"), open);
        }
    }
}
