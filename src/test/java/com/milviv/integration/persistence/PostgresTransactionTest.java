package com.milviv.integration.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.milviv.application.transaction.IsolationLevel;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionHolder;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/**
 * Транзакции и миграции на настоящем PostgreSQL.
 * Проверяет то, чего нет в H2: прерывание транзакции после ошибки
 * и настоящий serialization failure (SQLSTATE 40001).
 * Без Docker тест пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresTransactionTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private static PGSimpleDataSource dataSource;
    private static int migrationsApplied;

    private TransactionManager transactionManager;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        MigrateResult result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .load()
                .migrate();
        migrationsApplied = result.migrationsExecuted;
    }

    @BeforeEach
    void setUp() throws SQLException {
        transactionManager = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 5);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS tx_probe (v INT NOT NULL)");
            st.execute("TRUNCATE tx_probe");
        }
    }

    // ─── Миграции ─────────────────────────────────────────────────────────────

    @Test
    void migrations_shouldApplyOnPostgres() {
        assertThat(migrationsApplied).isEqualTo(15);
    }

    // ─── Rollback ─────────────────────────────────────────────────────────────

    @Test
    void rollback_shouldDiscardChanges_afterSqlErrorAbortsTransaction() throws SQLException {
        // when: после ошибки SQL PostgreSQL прерывает транзакцию
        assertThatThrownBy(() -> transactionManager.execute(() -> {
            insert(1);
            execute("INSERT INTO tx_probe (v) VALUES (NULL)"); // NOT NULL violation
        })).isInstanceOf(DatabaseException.class);

        // then
        assertThat(values()).isEmpty();
        assertThat(ConnectionHolder.isActive()).isFalse();
    }

    @Test
    void supports_shouldRejectWrites_whenNoOuterTransaction() throws SQLException {
        // when / then: SUPPORTS без внешней транзакции открывает read-only транзакцию
        assertThatThrownBy(() -> transactionManager.execute(Propagation.SUPPORTS, () -> {
            insert(1);
            return null;
        })).isInstanceOf(DatabaseException.class);

        assertThat(values()).isEmpty();
    }

    @Test
    void supports_shouldRead_whenNoOuterTransaction() throws SQLException {
        // given
        transactionManager.execute(() -> insert(7));

        // when
        List<Integer> read = transactionManager.execute(Propagation.SUPPORTS, () -> readValues());

        // then
        assertThat(read).containsExactly(7);
    }

    // ─── Serialization failure ────────────────────────────────────────────────

    @Test
    void serializationFailure_shouldBeRetried() throws Exception {
        // given: две SERIALIZABLE-транзакции читают одно и то же и обе пишут (write skew).
        // PostgreSQL отменит одну с 40001, менеджер повторит её.
        CountDownLatch bothRead = new CountDownLatch(2);
        AtomicInteger attempts = new AtomicInteger();

        Runnable writer = () -> transactionManager.execute(Propagation.REQUIRED, IsolationLevel.SERIALIZABLE, () -> {
            int count = countRows();
            if (attempts.incrementAndGet() <= 2) {
                bothRead.countDown();
                await(bothRead);
            }
            insert(count);
            return null;
        });

        // when
        CompletableFuture<Void> first = CompletableFuture.runAsync(writer);
        CompletableFuture<Void> second = CompletableFuture.runAsync(writer);
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        // then: одна транзакция повторилась и увидела запись другой
        assertThat(attempts).hasValue(3);
        assertThat(values()).containsExactlyInAnyOrder(0, 1);
    }

    // ─── Помощники ────────────────────────────────────────────────────────────

    private static void insert(int value) {
        try (PreparedStatement ps = ConnectionHolder.require().prepareStatement("INSERT INTO tx_probe (v) VALUES (?)")) {
            ps.setInt(1, value);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("insert failed", ex);
        }
    }

    private static void execute(String sql) {
        try (Statement st = ConnectionHolder.require().createStatement()) {
            st.execute(sql);
        } catch (SQLException ex) {
            throw new DatabaseException("statement failed", ex);
        }
    }

    private static int countRows() {
        try (Statement st = ConnectionHolder.require().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tx_probe")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException ex) {
            throw new DatabaseException("count failed", ex);
        }
    }

    private static List<Integer> readValues() {
        try (Statement st = ConnectionHolder.require().createStatement();
             ResultSet rs = st.executeQuery("SELECT v FROM tx_probe ORDER BY v")) {
            List<Integer> result = new ArrayList<>();
            while (rs.next()) {
                result.add(rs.getInt(1));
            }
            return result;
        } catch (SQLException ex) {
            throw new DatabaseException("read failed", ex);
        }
    }

    private static List<Integer> values() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT v FROM tx_probe ORDER BY v")) {
            List<Integer> result = new ArrayList<>();
            while (rs.next()) {
                result.add(rs.getInt(1));
            }
            return result;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("second transaction did not read in time");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
