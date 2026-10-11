package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiConsentService;
import com.milviv.application.ai.AiConsentService.AcceptResult;
import com.milviv.domain.exception.AiConsentRequiredException;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.AiConsentJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/** Согласие на AI на H2: статус, однократность, проверка, изоляция, одновременные согласия. */
class AiConsentJdbcH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private Connection keepAlive;
    private JdbcDataSource dataSource;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:consent" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        try (Statement st = keepAlive.createStatement()) {
            H2WorkSchema.create(st);
            for (UUID user : List.of(alice, bob)) {
                st.execute("INSERT INTO users (id, email, password_hash, name, created_at, updated_at) VALUES ('"
                        + user + "', '" + user + "@test.dev', 'x', 'U', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private AiConsentService service(Instant now) {
        return new AiConsentService(new AiConsentJdbcRepository(new TransactionalConnectionProvider()),
                new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void withoutConsent_aiIsUnavailable() {
        AiConsentService consents = service(NOW);

        assertThat(consents.status(alice)).isEqualTo(new AiConsentService.ConsentView(false, null));
        assertThatThrownBy(() -> consents.requireConsent(alice)).isInstanceOf(AiConsentRequiredException.class);
    }

    @Test
    void accept_isRecordedOnce_andKeepsOriginalDate() {
        AcceptResult first = service(NOW).accept(alice);
        AcceptResult repeat = service(NOW.plusSeconds(3600)).accept(alice);

        assertThat(first.created()).isTrue();
        assertThat(first.consent()).isEqualTo(new AiConsentService.ConsentView(true, NOW));
        assertThat(repeat.created()).isFalse();
        assertThat(repeat.consent().acceptedAt()).isEqualTo(NOW);
        assertThatCode(() -> service(NOW).requireConsent(alice)).doesNotThrowAnyException();
    }

    @Test
    void consent_isPerUser() {
        service(NOW).accept(alice);

        assertThat(service(NOW).status(bob).accepted()).isFalse();
        assertThatThrownBy(() -> service(NOW).requireConsent(bob)).isInstanceOf(AiConsentRequiredException.class);
    }

    @Test
    void concurrentAccepts_shouldRecordExactlyOne() throws Exception {
        AiConsentService consents = service(NOW);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<AcceptResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return consents.accept(alice);
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<AcceptResult> result : results) {
            created += result.get().created() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        try (Statement st = keepAlive.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ai_consents WHERE user_id = '" + alice + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
