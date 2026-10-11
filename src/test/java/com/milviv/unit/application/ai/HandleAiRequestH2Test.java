package com.milviv.unit.application.ai;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiConsentService;
import com.milviv.application.ai.AiProviderException.Kind;
import com.milviv.application.ai.AiPlanPolicy;
import com.milviv.application.ai.AiTurnValidator;
import com.milviv.application.ai.HandleAiRequest;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.RefusalReason;
import com.milviv.domain.exception.AiConsentRequiredException;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.AiRequestException.Code;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.AiConsentJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.AiRequestJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.unit.application.ai.FakeAiAssistant.MutableClock;
import com.milviv.unit.infrastructure.persistence.H2TestUsers;

/** Фаза 1 AI-запроса на H2 + FakeAiAssistant: согласие, исходы, повтор, активный запрос, сбои, таймауты. */
class HandleAiRequestH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final String MESSAGE = "Какие задачи я чаще всего просрочиваю?";
    static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private Connection keepAlive;
    private JdbcDataSource dataSource;
    private TransactionManager tx;
    private TransactionalConnectionProvider connections;
    private AiConsentService consents;
    private final MutableClock clock = new MutableClock(NOW);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:airequests" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        H2TestUsers.createSchemaWithUsers(keepAlive, alice, bob);

        connections = new TransactionalConnectionProvider();
        tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
        consents = new AiConsentService(new AiConsentJdbcRepository(connections), tx, clock);
        consents.accept(alice);
        consents.accept(bob);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private HandleAiRequest handler(FakeAiAssistant ai) {
        return handler(ai, 4);
    }

    private HandleAiRequest handler(FakeAiAssistant ai, int maxCalls) {
        var limits = AiTurnValidatorTest.limits(maxCalls, Duration.ofSeconds(90));
        return new HandleAiRequest(consents, ai, new AiTurnValidator(limits, new AiPlanPolicy(limits)),
                AiCreationsH2Test.executeAiPlan(connections, tx, clock), AiCreationsH2Test.catalog(connections, limits, tx),
                new AiRequestJdbcRepository(connections, MAPPER),
                limits, tx, clock);
    }

    private static Code code(Throwable ex) {
        return ((AiRequestException) ex).getCode();
    }

    // ─── Согласие и ввод ──────────────────────────────────────────────────────

    @Test
    void withoutConsent_providerIsNotCalled_andNothingIsStored() throws SQLException {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(new AiTurn.Answer("x"));
        UUID carol = UUID.randomUUID();
        H2TestUsers.insert(keepAlive, carol);

        assertThatThrownBy(() -> handler(ai).execute(carol, "k1", MESSAGE)).isInstanceOf(AiConsentRequiredException.class);
        assertThatThrownBy(() -> handler(ai).list(carol, 50, 0)).isInstanceOf(AiConsentRequiredException.class);
        assertThat(ai.prompts()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM ai_requests")).isZero();
    }

    @Test
    void input_isValidated_beforeProvider() {
        FakeAiAssistant ai = new FakeAiAssistant();
        HandleAiRequest handler = handler(ai);

        assertThatThrownBy(() -> handler.execute(alice, null, MESSAGE)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> handler.execute(alice, "bad key!", MESSAGE)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> handler.execute(alice, "k1", "   ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> handler.execute(alice, "k1", "x".repeat(2001))).isInstanceOf(ValidationException.class);
        assertThat(ai.prompts()).isEmpty();
    }

    // ─── Исходы ───────────────────────────────────────────────────────────────

    @Test
    void answer_isStored_withoutRawMessage() throws SQLException {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(new AiTurn.Answer("  Чаще всего — задачи по грамматике  "));

        HandleAiRequest.Result result = handler(ai).execute(alice, "k1", MESSAGE);

        assertThat(result.created()).isTrue();
        assertThat(result.request().status()).isEqualTo(AiRequestStatus.ANSWERED);
        assertThat(result.request().answer()).isEqualTo("Чаще всего — задачи по грамматике");
        assertThat(ai.prompts()).singleElement().satisfies(prompt -> {
            assertThat(prompt.userMessage()).isEqualTo(MESSAGE);
            assertThat(prompt.timezone()).isEqualTo("UTC");
            assertThat(prompt.contractVersion()).isEqualTo("assistant.v1");
        });
        assertThat(count("SELECT COUNT(*) FROM ai_requests WHERE message_length = " + MESSAGE.length()
                + " AND ai_calls = 1 AND input_tokens = 10 AND provider = 'fake'")).isEqualTo(1);
        // текст сообщения не хранится ни в одной колонке
        assertThat(count("SELECT COUNT(*) FROM ai_requests WHERE CAST(answer AS VARCHAR) LIKE '%просрочиваю%'"
                + " OR message_hash LIKE '%просрочиваю%'")).isZero();
    }

    @Test
    void clarification_andRefusal() {
        HandleAiRequest.Result clarification = handler(new FakeAiAssistant()
                .thenTurn(new AiTurn.ClarificationNeeded("Какой проект?"))).execute(alice, "k1", MESSAGE);
        assertThat(clarification.request().status()).isEqualTo(AiRequestStatus.NEEDS_CLARIFICATION);
        assertThat(clarification.request().clarificationQuestion()).isEqualTo("Какой проект?");

        HandleAiRequest refusing = handler(new FakeAiAssistant().thenTurn(new AiTurn.Refused(RefusalReason.UNSUPPORTED_OPERATION)));
        assertThatThrownBy(() -> refusing.execute(alice, "k2", "Выполни задачу"))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_REFUSED))
                .hasMessage("Эта операция недоступна для AI");
        assertThat(handler(new FakeAiAssistant()).list(alice, 50, 0))
                .extracting(view -> view.status())
                .containsExactlyInAnyOrder(AiRequestStatus.NEEDS_CLARIFICATION, AiRequestStatus.FAILED);
    }

    // ─── Повтор и активный запрос (§10.2) ─────────────────────────────────────

    @Test
    void sameKey_returnsStoredResult_withoutCallingAiAgain() {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(new AiTurn.Answer("Ответ"));
        HandleAiRequest handler = handler(ai);

        HandleAiRequest.Result first = handler.execute(alice, "k1", MESSAGE);
        HandleAiRequest.Result repeat = handler.execute(alice, "k1", MESSAGE);

        assertThat(repeat.created()).isFalse();
        assertThat(repeat.request()).isEqualTo(first.request());
        assertThat(ai.prompts()).hasSize(1);

        assertThatThrownBy(() -> handler.execute(alice, "k1", "Другое сообщение"))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void sameKey_afterFailure_returnsSameError() {
        FakeAiAssistant ai = new FakeAiAssistant().thenFail(Kind.UNAVAILABLE);
        HandleAiRequest handler = handler(ai);

        assertThatThrownBy(() -> handler.execute(alice, "k1", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_PROVIDER_UNAVAILABLE));
        assertThatThrownBy(() -> handler.execute(alice, "k1", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_PROVIDER_UNAVAILABLE));
        assertThat(ai.prompts()).hasSize(1);
    }

    @Test
    void secondRequest_whileFirstIsPending_isRejected() {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(new AiTurn.Answer("Ответ"));
        HandleAiRequest handler = handler(ai);
        // пока идёт «вызов AI», пользователь отправляет второй запрос
        ai.duringCall(prompt -> assertThatThrownBy(() -> handler.execute(alice, "k2", "Второй"))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_REQUEST_IN_PROGRESS)));

        assertThat(handler.execute(alice, "k1", MESSAGE).request().status()).isEqualTo(AiRequestStatus.ANSWERED);
        assertThat(ai.prompts()).hasSize(1);
    }

    @Test
    void stalePending_isFailed_andUserCanContinue() throws SQLException {
        insertPending(alice, "k-old", NOW.minusSeconds(91));
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(new AiTurn.Answer("Ответ"));

        assertThat(handler(ai).execute(alice, "k1", MESSAGE).request().status()).isEqualTo(AiRequestStatus.ANSWERED);
        assertThat(count("SELECT COUNT(*) FROM ai_requests WHERE idempotency_key = 'k-old'"
                + " AND status = 'FAILED' AND error_code = 'AI_TIMEOUT'")).isEqualTo(1);
    }

    @Test
    void freshPending_ofAnotherKey_blocks() {
        insertPending(alice, "k-old", NOW.minusSeconds(10));

        assertThatThrownBy(() -> handler(new FakeAiAssistant()).execute(alice, "k1", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_REQUEST_IN_PROGRESS));
        // у другого пользователя своё правило
        assertThat(handler(new FakeAiAssistant().thenTurn(new AiTurn.Answer("Ответ"))).execute(bob, "k1", MESSAGE)
                .request().status()).isEqualTo(AiRequestStatus.ANSWERED);
    }

    // ─── Неверный ход, сбои, таймауты ─────────────────────────────────────────

    @Test
    void invalidTurn_getsOneRepair_withViolations() {
        FakeAiAssistant ai = new FakeAiAssistant()
                .thenTurn(new AiTurn.Answer(" "))
                .thenTurn(new AiTurn.Answer("Исправленный ответ"));

        assertThat(handler(ai).execute(alice, "k1", MESSAGE).request().answer()).isEqualTo("Исправленный ответ");
        assertThat(ai.prompts()).extracting(prompt -> prompt.violations())
                .containsExactly(List.of(), List.of("answer: REQUIRED"));
    }

    @Test
    void invalidTwice_orMalformedTwice_isInvalidResponse() {
        FakeAiAssistant invalid = new FakeAiAssistant().thenTurn(new AiTurn.Answer(" ")).thenTurn(new AiTurn.Answer(" "));
        assertThatThrownBy(() -> handler(invalid).execute(alice, "k1", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_INVALID_RESPONSE));

        FakeAiAssistant malformed = new FakeAiAssistant().thenFail(Kind.MALFORMED).thenFail(Kind.MALFORMED);
        assertThatThrownBy(() -> handler(malformed).execute(alice, "k2", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_INVALID_RESPONSE));
        assertThat(malformed.prompts()).hasSize(2);
    }

    @Test
    void maxCallsPerRequest_limitsRepair() {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(new AiTurn.Answer(" ")).thenTurn(new AiTurn.Answer("ok"));

        assertThatThrownBy(() -> handler(ai, 1).execute(alice, "k1", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_INVALID_RESPONSE));
        assertThat(ai.prompts()).hasSize(1);
    }

    @Test
    void providerFailures_mapToCodes() {
        assertThatThrownBy(() -> handler(new FakeAiAssistant().thenFail(Kind.TIMEOUT)).execute(alice, "k1", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_TIMEOUT));
        assertThatThrownBy(() -> handler(new FakeAiAssistant().thenFail(Kind.UNAVAILABLE)).execute(alice, "k2", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_PROVIDER_UNAVAILABLE));
    }

    @Test
    void requestTimeout_coversAllCalls() {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(new AiTurn.Answer("поздно"))
                .duringCall(prompt -> clock.advance(Duration.ofSeconds(91)));

        assertThatThrownBy(() -> handler(ai).execute(alice, "k1", MESSAGE))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo(Code.AI_TIMEOUT));
    }

    // ─── Чтение ───────────────────────────────────────────────────────────────

    @Test
    void foreignRequest_isNotFound() {
        HandleAiRequest.Result result = handler(new FakeAiAssistant().thenTurn(new AiTurn.Answer("Ответ")))
                .execute(alice, "k1", MESSAGE);
        HandleAiRequest reader = handler(new FakeAiAssistant());

        assertThat(reader.get(alice, result.request().requestId())).isEqualTo(result.request());
        assertThatThrownBy(() -> reader.get(bob, result.request().requestId())).isInstanceOf(NotFoundException.class);
        assertThat(reader.list(bob, 50, 0)).isEmpty();
    }

    /** Запрос, «застрявший» в PENDING_AI: сервер упал во время вызова AI. */
    private void insertPending(UUID user, String key, Instant createdAt) {
        var repository = new AiRequestJdbcRepository(connections, MAPPER);
        tx.execute(() -> repository.add(com.milviv.domain.ai.AiRequest.start(user, key, "h", 1, "h",
                "assistant.v1", "assistant@1", "fake", "fake-1", createdAt)));
    }

    private int count(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
