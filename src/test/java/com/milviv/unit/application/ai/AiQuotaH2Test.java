package com.milviv.unit.application.ai;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiConsentService;
import com.milviv.application.ai.AiLimits;
import com.milviv.application.ai.AiPlanPolicy;
import com.milviv.application.ai.AiProviderException.Kind;
import com.milviv.application.ai.AiTurnValidator;
import com.milviv.application.ai.AiUsageQuota;
import com.milviv.application.ai.HandleAiRequest;
import com.milviv.application.ai.ProviderQuotaBreaker;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.application.ai.contract.PlanDraft.Create;
import com.milviv.application.ai.contract.PlanDraft.TaskNode;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.AiRequestException.Code;
import com.milviv.domain.task.Difficulty;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.AiConsentJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.AiRequestJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.unit.application.ai.FakeAiAssistant.MutableClock;
import com.milviv.unit.infrastructure.persistence.H2TestUsers;

/** Квоты на пользователя и предохранитель квоты провайдера (§8, T-1, T-4) на H2. */
class AiQuotaH2Test {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    private Connection keepAlive;
    private TransactionManager tx;
    private TransactionalConnectionProvider connections;
    private AiConsentService consents;
    private final MutableClock clock = new MutableClock(NOW);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:aiquota" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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

    /** Лимиты §8, кроме квот, — их тест задаёт маленькими. */
    private static AiLimits limits(int perDay, int perMinute, int createdPerDay) {
        AiLimits base = AiTurnValidatorTest.limits(4, Duration.ofSeconds(90));
        AiLimits.Request r = base.request();
        return new AiLimits(new AiLimits.Request(r.messageMaxLength(), perDay, perMinute, r.maxCallsPerRequest(),
                r.requestTimeout(), r.planTtl(), createdPerDay, Duration.ofMinutes(60)), base.data(), base.plan(), base.memory());
    }

    private HandleAiRequest handler(FakeAiAssistant ai, AiLimits limits, ProviderQuotaBreaker breaker) {
        return new HandleAiRequest(consents, ai, new AiTurnValidator(limits, new AiPlanPolicy(limits)),
                AiCreationsH2Test.executeAiPlan(connections, tx, clock), AiCreationsH2Test.previewBuilder(connections, tx, clock),
                AiCreationsH2Test.catalog(connections, limits, tx),
                new AiRequestJdbcRepository(connections, HandleAiRequestH2Test.MAPPER), limits,
                new AiUsageQuota(new AiRequestJdbcRepository(connections, HandleAiRequestH2Test.MAPPER), limits),
                breaker, new com.milviv.infrastructure.persistence.jdbc.AiMemoryJdbcRepository(connections), tx, clock);
    }

    private HandleAiRequest handler(FakeAiAssistant ai, AiLimits limits) {
        return handler(ai, limits, new ProviderQuotaBreaker(limits));
    }

    private static FakeAiAssistant answers(int count) {
        FakeAiAssistant ai = new FakeAiAssistant();
        IntStream.range(0, count).forEach(i -> ai.thenTurn(new AiTurn.Answer("Ответ " + i)));
        return ai;
    }

    private static AiTurn.Proposal tasks(int count) {
        List<TaskNode> tasks = IntStream.range(0, count)
                .mapToObj(i -> new TaskNode("Задача " + i, null, Difficulty.NOVICE, null, null, null))
                .toList();
        return new AiTurn.Proposal("Задачи", null, new PlanDraft(new Create(null, null, tasks, null)));
    }

    private static AiRequestException rejected(Runnable call) {
        AiRequestException ex = catchThrowableOfType(AiRequestException.class, call::run);
        assertThat(ex).isNotNull();
        return ex;
    }

    private int count(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ─── Запросы ──────────────────────────────────────────────────────────────

    @Test
    void perMinute_isRollingWindow_andRejectedRequestIsNotStored() throws SQLException {
        HandleAiRequest handler = handler(answers(4), limits(20, 3, 200));
        for (int i = 1; i <= 3; i++) {
            handler.execute(alice, "k" + i, "Вопрос " + i);
        }

        AiRequestException ex = rejected(() -> handler.execute(alice, "k4", "Вопрос 4"));
        assertThat(ex.getCode()).isEqualTo(Code.AI_QUOTA_EXCEEDED);
        assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(1));
        assertThat(count("SELECT COUNT(*) FROM ai_requests")).isEqualTo(3);

        // повтор с тем же ключом — не новый запрос
        assertThat(handler.execute(alice, "k1", "Вопрос 1").created()).isFalse();
        // другой пользователь — своя квота
        assertThat(handler(answers(1), limits(20, 3, 200)).execute(bob, "k1", "Вопрос").created()).isTrue();

        clock.advance(Duration.ofSeconds(61));
        assertThat(handler.execute(alice, "k4", "Вопрос 4").request().status()).isEqualTo(AiRequestStatus.ANSWERED);
    }

    @Test
    void perDay_countsFailedRequests() {
        FakeAiAssistant ai = new FakeAiAssistant()
                .thenTurn(new AiTurn.Answer("1")).thenFail(Kind.UNAVAILABLE).thenTurn(new AiTurn.Answer("3"));
        HandleAiRequest handler = handler(ai, limits(3, 10, 200));
        handler.execute(alice, "k1", "Вопрос 1");
        rejected(() -> handler.execute(alice, "k2", "Вопрос 2"));
        handler.execute(alice, "k3", "Вопрос 3");
        clock.advance(Duration.ofMinutes(2));

        AiRequestException ex = rejected(() -> handler.execute(alice, "k4", "Вопрос 4"));
        assertThat(ex.getCode()).isEqualTo(Code.AI_QUOTA_EXCEEDED);
        assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofDays(1).minusMinutes(2));
        assertThat(ai.prompts()).hasSize(3);
    }

    // ─── Созданий в сутки ─────────────────────────────────────────────────────

    @Test
    void createdPerDay_limitsPlans_andAiSeesWhatIsLeft() throws SQLException {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(tasks(4)).thenTurn(tasks(2)).thenTurn(tasks(1));
        HandleAiRequest handler = handler(ai, limits(20, 10, 5));

        handler.execute(alice, "k1", "Создай 4 задачи");
        assertThat(ai.prompts().get(0).limits().maxCreates()).isEqualTo(5);

        // остаток 1, план на 2 — отказ целиком, без обрезки и без повтора
        AiRequestException ex = rejected(() -> handler.execute(alice, "k2", "Создай ещё 2"));
        assertThat(ex.getCode()).isEqualTo(Code.AI_QUOTA_EXCEEDED);
        assertThat(ai.prompts().get(1).limits().maxCreates()).isEqualTo(1);
        assertThat(ai.prompts()).hasSize(2);
        assertThat(count("SELECT COUNT(*) FROM tasks")).isEqualTo(4);

        handler.execute(alice, "k3", "Создай одну");
        assertThat(count("SELECT COUNT(*) FROM tasks")).isEqualTo(5);

        // лимит исчерпан — AI не вызывается, запрос не записывается
        AiRequestException exhausted = rejected(() -> handler.execute(alice, "k4", "И ещё одну"));
        assertThat(exhausted.getCode()).isEqualTo(Code.AI_QUOTA_EXCEEDED);
        assertThat(exhausted.getRetryAfter()).isEqualTo(Duration.ofDays(1));
        assertThat(ai.prompts()).hasSize(3);

        clock.advance(Duration.ofDays(1).plusSeconds(1));
        FakeAiAssistant tomorrow = new FakeAiAssistant().thenTurn(tasks(1));
        handler(tomorrow, limits(20, 10, 5)).execute(alice, "k5", "Новый день");
        assertThat(tomorrow.prompts().getFirst().limits().maxCreates()).isEqualTo(5);
    }

    // ─── Предохранитель квоты провайдера ──────────────────────────────────────

    @Test
    void providerQuotaExhausted_opensBreaker_forNewRequestsOnly() throws SQLException {
        AiLimits limits = limits(20, 10, 200);
        ProviderQuotaBreaker breaker = new ProviderQuotaBreaker(limits);
        FakeAiAssistant ai = new FakeAiAssistant().thenFail(Kind.QUOTA_EXHAUSTED).thenTurn(new AiTurn.Answer("Снова работаю"));
        HandleAiRequest handler = handler(ai, limits, breaker);

        AiRequestException first = rejected(() -> handler.execute(alice, "k1", "Вопрос"));
        assertThat(first.getCode()).isEqualTo(Code.AI_PROVIDER_QUOTA_EXHAUSTED);
        assertThat(first.getRetryAfter()).isEqualTo(Duration.ofMinutes(60));

        // новый запрос — без вызова AI и без записи
        AiRequestException next = rejected(() -> handler.execute(bob, "k1", "Другой вопрос"));
        assertThat(next.getCode()).isEqualTo(Code.AI_PROVIDER_QUOTA_EXHAUSTED);
        assertThat(ai.prompts()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM ai_requests")).isEqualTo(1);

        // повтор уже записанного — тот же итог, со сроком предохранителя
        clock.advance(Duration.ofMinutes(10));
        AiRequestException replay = rejected(() -> handler.execute(alice, "k1", "Вопрос"));
        assertThat(replay.getCode()).isEqualTo(Code.AI_PROVIDER_QUOTA_EXHAUSTED);
        assertThat(replay.getRetryAfter()).isEqualTo(Duration.ofMinutes(50));

        clock.advance(Duration.ofMinutes(51));
        assertThat(handler.execute(bob, "k2", "Другой вопрос").request().status()).isEqualTo(AiRequestStatus.ANSWERED);
        assertThat(ai.prompts()).hasSize(2);
    }

    @Test
    void breaker_parallelFailures_doNotShortenOpenTime() {
        ProviderQuotaBreaker breaker = new ProviderQuotaBreaker(limits(20, 3, 200));

        assertThat(breaker.retryAfter(NOW)).isEmpty();
        breaker.open(NOW.plusSeconds(600));
        breaker.open(NOW);
        assertThat(breaker.retryAfter(NOW)).contains(Duration.ofMinutes(70));
        assertThat(breaker.retryAfter(NOW.plus(Duration.ofMinutes(70)))).isEmpty();
    }
}
