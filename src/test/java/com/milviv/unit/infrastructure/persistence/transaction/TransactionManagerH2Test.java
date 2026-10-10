package com.milviv.unit.infrastructure.persistence.transaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionHolder;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/**
 * Транзакции на настоящей БД (H2 in-memory): commit, rollback, REQUIRES_NEW,
 * оптимистичная блокировка с повтором.
 */
class TransactionManagerH2Test {

    private JdbcDataSource dataSource;
    private TransactionManager transactionManager;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:tx" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        keepAlive = dataSource.getConnection();
        try (Statement st = keepAlive.createStatement()) {
            st.execute("CREATE TABLE counter (id INT PRIMARY KEY, val INT NOT NULL, version BIGINT NOT NULL)");
            st.execute("CREATE TABLE log_entry (id INT AUTO_INCREMENT PRIMARY KEY, message VARCHAR(100))");
            st.execute("INSERT INTO counter VALUES (1, 100, 0)");
        }

        transactionManager = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
    }

    @AfterEach
    void tearDown() throws SQLException {
        ConnectionHolder.clear();
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    // ─── Commit и rollback ────────────────────────────────────────────────────

    @Test
    void commit_shouldPersistAllChanges() throws SQLException {
        // when
        transactionManager.execute(() -> {
            log("first");
            log("second");
        });

        // then
        assertThat(logCount()).isEqualTo(2);
    }

    @Test
    void rollback_shouldDiscardAllChanges_whenActionFails() throws SQLException {
        // when / then
        assertThatThrownBy(() -> transactionManager.execute(() -> {
            log("first");
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(logCount()).isZero();
    }

    @Test
    void requiresNew_shouldCommitIndependently_whenOuterRollsBack() throws SQLException {
        // when / then
        assertThatThrownBy(() -> transactionManager.execute(() -> {
            log("outer");
            transactionManager.execute(Propagation.REQUIRES_NEW, () -> {
                log("audit");
                return null;
            });
            throw new IllegalStateException("outer fails");
        })).hasMessage("outer fails");

        assertThat(logMessages()).containsExactly("audit");
    }

    // ─── Оптимистичная блокировка ─────────────────────────────────────────────

    @Test
    void optimisticLock_shouldRetryWholeTransaction_andApplyBothUpdates() throws SQLException {
        // given: при первой попытке кто-то другой успевает изменить счётчик
        AtomicInteger attempts = new AtomicInteger();

        // when
        transactionManager.execute(() -> {
            long version = readVersion();
            int value = readValue();
            if (attempts.incrementAndGet() == 1) {
                concurrentAdd(50);
            }
            updateCounter(value + 20, version);
        });

        // then: 100 + 50 (конкурент) + 20 (наша повторная попытка)
        assertThat(attempts).hasValue(2);
        assertThat(currentValue()).isEqualTo(170);
    }

    @Test
    void optimisticLock_shouldGiveUp_afterMaxAttempts() throws SQLException {
        // when / then: конкурент мешает при каждой попытке
        assertThatThrownBy(() -> transactionManager.execute(() -> {
            long version = readVersion();
            int value = readValue();
            concurrentAdd(1);
            updateCounter(value + 20, version);
        })).isInstanceOf(OptimisticLockException.class);

        assertThat(currentValue()).isEqualTo(103); // только три вмешательства конкурента
    }

    @Test
    void nestedCall_shouldNotDuplicateWrites_whenInnerConflicts() throws SQLException {
        // given: вложенный вызов падает с конфликтом версий при первой попытке
        AtomicInteger innerAttempts = new AtomicInteger();

        // when
        transactionManager.execute(() -> {
            log("outer");
            transactionManager.execute(Propagation.REQUIRED, () -> {
                log("inner");
                if (innerAttempts.incrementAndGet() == 1) {
                    throw new OptimisticLockException("Counter", UUID.randomUUID(), 0);
                }
                return null;
            });
        });

        // then: повторилась вся транзакция, а не вложенный вызов внутри неё
        assertThat(innerAttempts).hasValue(2);
        assertThat(logMessages()).containsExactly("outer", "inner");
    }

    // ─── Помощники: работают через соединение текущей транзакции ──────────────

    private void log(String message) {
        try (PreparedStatement ps = ConnectionHolder.require()
                .prepareStatement("INSERT INTO log_entry (message) VALUES (?)")) {
            ps.setString(1, message);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("log failed", ex);
        }
    }

    private long readVersion() {
        return queryLong(ConnectionHolder.require(), "SELECT version FROM counter WHERE id = 1");
    }

    private int readValue() {
        return (int) queryLong(ConnectionHolder.require(), "SELECT val FROM counter WHERE id = 1");
    }

    private void updateCounter(int newValue, long expectedVersion) {
        try (PreparedStatement ps = ConnectionHolder.require().prepareStatement(
                "UPDATE counter SET val = ?, version = version + 1 WHERE id = 1 AND version = ?")) {
            ps.setInt(1, newValue);
            ps.setLong(2, expectedVersion);
            if (ps.executeUpdate() == 0) {
                throw new OptimisticLockException("Counter", UUID.randomUUID(), expectedVersion);
            }
        } catch (SQLException ex) {
            throw new DatabaseException("update failed", ex);
        }
    }

    /** Изменение от «другого пользователя» — отдельное соединение с autocommit. */
    private void concurrentAdd(int delta) {
        try (Connection other = dataSource.getConnection();
             PreparedStatement ps = other.prepareStatement(
                     "UPDATE counter SET val = val + ?, version = version + 1 WHERE id = 1")) {
            ps.setInt(1, delta);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("concurrent update failed", ex);
        }
    }

    private int currentValue() {
        return (int) queryLong(keepAlive, "SELECT val FROM counter WHERE id = 1");
    }

    private int logCount() {
        return (int) queryLong(keepAlive, "SELECT COUNT(*) FROM log_entry");
    }

    private java.util.List<String> logMessages() throws SQLException {
        java.util.List<String> messages = new java.util.ArrayList<>();
        try (Statement st = keepAlive.createStatement();
             ResultSet rs = st.executeQuery("SELECT message FROM log_entry ORDER BY id")) {
            while (rs.next()) {
                messages.add(rs.getString(1));
            }
        }
        return messages;
    }

    private static long queryLong(Connection connection, String sql) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException ex) {
            throw new DatabaseException("query failed", ex);
        }
    }
}
