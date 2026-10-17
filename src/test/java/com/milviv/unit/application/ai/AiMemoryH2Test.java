package com.milviv.unit.application.ai;

import java.sql.Connection;
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
import com.milviv.application.ai.AiLimits;
import com.milviv.application.ai.AiMemoryCommands;
import com.milviv.application.ai.AiMemoryCommands.AiMemoryView;
import com.milviv.application.ai.AiPlanPolicy;
import com.milviv.application.ai.AiTurnValidator;
import com.milviv.application.ai.HandleAiRequest;
import com.milviv.application.ai.ProviderQuotaBreaker;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.DataQuery;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.ai.DataAccess;
import com.milviv.domain.exception.AiConsentRequiredException;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.AiConsentJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.AiMemoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.AiRequestJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.unit.application.ai.FakeAiAssistant.MutableClock;
import com.milviv.unit.infrastructure.persistence.H2TestUsers;

/** Память AI на H2: пишет только пользователь, AI получает её в каждом вызове (AI Memory). */
class AiMemoryH2Test {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    private Connection keepAlive;
    private TransactionManager tx;
    private TransactionalConnectionProvider connections;
    private AiConsentService consents;
    private AiMemoryCommands memories;
    private final MutableClock clock = new MutableClock(NOW);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final AiLimits limits = small();

    /** Лимиты §8, память — 3 записи по 40 символов. */
    private static AiLimits small() {
        AiLimits base = AiTurnValidatorTest.limits(4, Duration.ofSeconds(90));
        return new AiLimits(base.request(), base.data(), base.plan(), new AiLimits.Memory(3, 40));
    }

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:aimemory" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        H2TestUsers.createSchemaWithUsers(keepAlive, alice, bob);
        connections = new TransactionalConnectionProvider();
        tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
        consents = new AiConsentService(new AiConsentJdbcRepository(connections), tx, clock);
        consents.accept(alice);
        memories = new AiMemoryCommands(consents, new AiMemoryJdbcRepository(connections), limits, tx, clock);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private HandleAiRequest handler(FakeAiAssistant ai) {
        return new HandleAiRequest(consents, ai, new AiTurnValidator(limits, new AiPlanPolicy(limits)),
                AiCreationsH2Test.executeAiPlan(connections, tx, clock), AiCreationsH2Test.previewBuilder(connections, tx, clock),
                AiCreationsH2Test.catalog(connections, limits, tx),
                new AiRequestJdbcRepository(connections, HandleAiRequestH2Test.MAPPER), limits,
                AiCreationsH2Test.usageQuota(connections, limits), new ProviderQuotaBreaker(limits),
                new AiMemoryJdbcRepository(connections), tx, clock);
    }

    // ─── Сценарий памяти ──────────────────────────────────────────────────────

    @Test
    void create_list_delete_newestFirst() {
        AiMemoryView evening = memories.create(alice, "  Сложное — вечером  ");
        clock.advance(Duration.ofMinutes(1));
        memories.create(alice, "Уведомления мешают");

        assertThat(evening.content()).isEqualTo("Сложное — вечером");
        assertThat(memories.list(alice)).extracting(AiMemoryView::content)
                .containsExactly("Уведомления мешают", "Сложное — вечером");

        memories.delete(alice, evening.id());
        assertThat(memories.list(alice)).extracting(AiMemoryView::content).containsExactly("Уведомления мешают");
        assertThatThrownBy(() -> memories.delete(alice, evening.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void otherUsersMemory_isInvisible_andCannotBeDeleted() {
        AiMemoryView mine = memories.create(alice, "Мой вывод");
        consents.accept(bob);

        assertThat(memories.list(bob)).isEmpty();
        assertThatThrownBy(() -> memories.delete(bob, mine.id())).isInstanceOf(NotFoundException.class);
        assertThat(memories.list(alice)).hasSize(1);
    }

    @Test
    void text_isChecked_andCountIsLimited() {
        assertThatThrownBy(() -> memories.create(alice, "   ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> memories.create(alice, "x".repeat(41))).isInstanceOf(ValidationException.class);

        memories.create(alice, "Один");
        memories.create(alice, "Два");
        memories.create(alice, "Три");
        assertThatThrownBy(() -> memories.create(alice, "Четыре"))
                .isInstanceOfSatisfying(AiRequestException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(AiRequestException.Code.AI_MEMORY_FULL));
    }

    @Test
    void withoutConsent_memoryIsUnavailable() {
        assertThatThrownBy(() -> memories.list(bob)).isInstanceOf(AiConsentRequiredException.class);
        assertThatThrownBy(() -> memories.create(bob, "Вывод")).isInstanceOf(AiConsentRequiredException.class);
        assertThatThrownBy(() -> memories.delete(bob, UUID.randomUUID())).isInstanceOf(AiConsentRequiredException.class);
    }

    // ─── Память в запросах к AI ───────────────────────────────────────────────

    @Test
    void memory_goesToEveryCall_andIsLogged() {
        memories.create(alice, "Сложное — вечером");
        clock.advance(Duration.ofMinutes(1));
        memories.create(alice, "Не больше трёх задач в день");
        consents.accept(bob);
        memories.create(bob, "Чужой вывод");

        FakeAiAssistant ai = new FakeAiAssistant()
                .thenTurn(new AiTurn.DataRequest("Посмотреть задачи", List.of(new DataQuery.ListTasks(null, null, null, null, null))))
                .thenTurn(new AiTurn.Answer("Ставьте сложное на вечер"));

        var view = handler(ai).execute(alice, "k1", "Когда мне делать сложные задачи?").request();

        assertThat(ai.prompts()).hasSize(2).allSatisfy(prompt -> assertThat(prompt.memories())
                .containsExactly("Не больше трёх задач в день", "Сложное — вечером"));
        assertThat(view.dataAccessed()).first().isEqualTo(new DataAccess("MEMORY", 2, null));
        assertThat(view.dataAccessed()).extracting(DataAccess::query).containsExactly("MEMORY", "LIST_TASKS");
    }

    @Test
    void withoutMemory_nothingIsLogged() {
        FakeAiAssistant ai = new FakeAiAssistant().thenTurn(new AiTurn.Answer("Ответ"));

        var view = handler(ai).execute(alice, "k1", "Вопрос").request();

        assertThat(ai.prompts().getFirst().memories()).isEmpty();
        assertThat(view.dataAccessed()).isEmpty();
    }
}
