package com.milviv.unit.infrastructure.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import com.milviv.application.ai.AiAssistantPort.AiPrompt;
import com.milviv.application.ai.AiAssistantPort.AiReply;
import com.milviv.application.ai.AiProviderException;
import com.milviv.application.ai.AiProviderException.Kind;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.infrastructure.ai.gemini.GeminiAssistantAdapter;
import com.milviv.infrastructure.ai.gemini.GeminiSettings;
import com.milviv.unit.application.ai.AiTurnValidatorTest;

/**
 * Адаптер Gemini на HTTP-заглушке (JDK HttpServer) с ответами в формате Gemini (§12).
 * Ответы собраны по документации; после онлайн-прогона заменяются записанными.
 */
class GeminiAssistantAdapterTest {

    private static final String MODEL = "gemini-flash-test";

    /** Ответ заглушки: статус, тело, задержка. */
    private record Reply(int status, String body, Duration delay) {}

    private record Received(String path, String apiKey, String query, String body) {}

    private HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Received> received = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            synchronized (received) {
                received.add(new Received(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("x-goog-api-key"), exchange.getRequestURI().getQuery(),
                        new String(request, StandardCharsets.UTF_8)));
            }
            Reply reply;
            synchronized (replies) {
                reply = replies.isEmpty() ? new Reply(500, "{}", Duration.ZERO) : replies.poll();
            }
            try {
                Thread.sleep(reply.delay());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(reply.status(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException ex) {
                // клиент ушёл по таймауту
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiAssistantAdapter adapter(int retries, Duration callTimeout, int maxBytes) {
        return adapter(retries, callTimeout, maxBytes, Duration.ofMillis(5));
    }

    private GeminiAssistantAdapter adapter(int retries, Duration callTimeout, int maxBytes, Duration backoff) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new GeminiAssistantAdapter(new GeminiSettings("test-key", MODEL, base, Duration.ofSeconds(2), callTimeout,
                retries, backoff, 8192, 0.4, maxBytes), AiTurnValidatorTest.limits(4, Duration.ofSeconds(90)));
    }

    private GeminiAssistantAdapter adapter() {
        return adapter(2, Duration.ofSeconds(5), 65_536);
    }

    private static String gemini(String name) {
        try (InputStream in = GeminiAssistantAdapterTest.class.getResourceAsStream("/gemini/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void then(int status, String file) {
        replies.add(new Reply(status, gemini(file), Duration.ZERO));
    }

    private static AiPrompt prompt(Duration timeLeft) {
        AiPrompt base = AiPromptRendererTest.prompt("Какие задачи я просрочиваю?", List.of(), List.of());
        return new AiPrompt(base.contractVersion(), base.promptVersion(), base.locale(), base.timezone(), base.today(),
                base.userMessage(), base.call(), base.violations(), timeLeft, base.limits(), base.rounds(), base.memories());
    }

    private static AiPrompt prompt() {
        return prompt(Duration.ofSeconds(60));
    }

    private Kind failure(GeminiAssistantAdapter adapter, AiPrompt prompt) {
        AiProviderException ex = catchThrowableOfType(AiProviderException.class, () -> adapter.nextTurn(prompt));
        assertThat(ex).isNotNull();
        return ex.getKind();
    }

    // ─── Успех ────────────────────────────────────────────────────────────────

    @Test
    void answer_isParsed_withTokens_andRequestIsWellFormed() throws Exception {
        then(200, "answer-200.json");

        AiReply reply = adapter().nextTurn(prompt());

        assertThat(reply.turn()).isEqualTo(new AiTurn.Answer("Чаще всего просрочены задачи по грамматике"));
        assertThat(reply.inputTokens()).isEqualTo(1200);
        assertThat(reply.outputTokens()).isEqualTo(120); // ответ + «размышления»

        Received request = received.getFirst();
        assertThat(request.path()).isEqualTo("/v1beta/models/" + MODEL + ":generateContent");
        assertThat(request.apiKey()).isEqualTo("test-key");
        assertThat(request.query()).isNull(); // ключ — не в URL
        JsonNode body = new ObjectMapper().readTree(request.body());
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText()).contains("MILVIV");
        assertThat(body.path("contents").get(0).path("role").asText()).isEqualTo("user");
        JsonNode turn = new ObjectMapper().readTree(body.path("contents").get(0).path("parts").get(0).path("text").asText());
        assertThat(turn.path("userMessage").asText()).isEqualTo("Какие задачи я просрочиваю?");
        JsonNode config = body.path("generationConfig");
        assertThat(config.path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(config.path("responseJsonSchema").path("properties").has("outcome")).isTrue();
        assertThat(config.path("maxOutputTokens").asInt()).isEqualTo(8192);
    }

    @Test
    void thoughtParts_areIgnored() {
        then(200, "answer-with-thought-200.json");

        assertThat(adapter().nextTurn(prompt()).turn()).isInstanceOf(AiTurn.Answer.class);
    }

    // ─── Ретраи ───────────────────────────────────────────────────────────────

    @Test
    void serverErrors_areRetried_upToLimit() {
        then(503, "unavailable-503.json");
        then(503, "unavailable-503.json");
        then(200, "answer-200.json");
        assertThat(adapter().nextTurn(prompt()).turn()).isInstanceOf(AiTurn.Answer.class);
        assertThat(received).hasSize(3);

        received.clear();
        then(503, "unavailable-503.json");
        then(503, "unavailable-503.json");
        then(503, "unavailable-503.json");
        then(200, "answer-200.json");
        assertThat(failure(adapter(), prompt())).isEqualTo(Kind.UNAVAILABLE);
        assertThat(received).hasSize(3);
    }

    @Test
    void slowCall_timesOut_andRetryNeedsTimeLeft() {
        replies.add(new Reply(200, gemini("answer-200.json"), Duration.ofMillis(800)));
        replies.add(new Reply(200, gemini("answer-200.json"), Duration.ZERO));
        // таймаут вызова 300 мс, у запроса осталось 400 мс, пауза перед ретраем ≥ 1 с — ретрай не начинается
        assertThat(failure(adapter(2, Duration.ofMillis(300), 65_536, Duration.ofSeconds(1)), prompt(Duration.ofMillis(400))))
                .isEqualTo(Kind.TIMEOUT);
        assertThat(received).hasSize(1);

        // запас времени есть — ретрай после таймаута проходит
        received.clear();
        replies.clear();
        replies.add(new Reply(200, gemini("answer-200.json"), Duration.ofMillis(800)));
        replies.add(new Reply(200, gemini("answer-200.json"), Duration.ZERO));
        assertThat(adapter(2, Duration.ofMillis(300), 65_536).nextTurn(prompt(Duration.ofSeconds(5))).turn())
                .isInstanceOf(AiTurn.Answer.class);
        assertThat(received).hasSize(2);
    }

    @Test
    void rateLimit_isRetried_butDailyQuota_isNot() {
        then(429, "rate-limit-429.json");
        then(200, "answer-200.json");
        assertThat(adapter().nextTurn(prompt()).turn()).isInstanceOf(AiTurn.Answer.class);

        received.clear();
        then(429, "quota-daily-429.json");
        then(200, "answer-200.json");
        assertThat(failure(adapter(), prompt())).isEqualTo(Kind.QUOTA_EXHAUSTED);
        assertThat(received).hasSize(1);
    }

    @Test
    void clientErrors_areNotRetried() {
        then(400, "bad-request-400.json");
        then(200, "answer-200.json");

        assertThat(failure(adapter(), prompt())).isEqualTo(Kind.UNAVAILABLE);
        assertThat(received).hasSize(1);
    }

    // ─── Содержимое ответа ────────────────────────────────────────────────────

    @Test
    void providerVerdicts_mapToKinds() {
        then(200, "max-tokens-200.json");
        assertThat(failure(adapter(), prompt())).isEqualTo(Kind.OUTPUT_TRUNCATED);
        then(200, "safety-200.json");
        assertThat(failure(adapter(), prompt())).isEqualTo(Kind.REFUSED);
        then(200, "blocked-prompt-200.json");
        assertThat(failure(adapter(), prompt())).isEqualTo(Kind.REFUSED);
    }

    @Test
    void notContractText_andTooLargeResponse_areMalformed() {
        then(200, "not-contract-200.json");
        AiProviderException wrapped = catchThrowableOfType(AiProviderException.class, () -> adapter().nextTurn(prompt()));
        assertThat(wrapped.getKind()).isEqualTo(Kind.MALFORMED);
        assertThat(wrapped.getViolations()).containsExactly("response: NOT_JSON");

        then(200, "answer-200.json");
        AiProviderException large = catchThrowableOfType(AiProviderException.class,
                () -> adapter(2, Duration.ofSeconds(5), 100).nextTurn(prompt()));
        assertThat(large.getKind()).isEqualTo(Kind.MALFORMED);
        assertThat(large.getViolations()).containsExactly("response: TOO_LARGE");
    }
}
