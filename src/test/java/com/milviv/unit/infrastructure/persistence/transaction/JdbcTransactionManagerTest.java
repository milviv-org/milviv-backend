package com.milviv.unit.infrastructure.persistence.transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milviv.application.transaction.IsolationLevel;
import com.milviv.application.transaction.Propagation;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionHolder;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;

class JdbcTransactionManagerTest {

    private DataSource dataSource;
    private Connection outerConnection;
    private Connection innerConnection;
    private JdbcTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        outerConnection = mock(Connection.class);
        innerConnection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(outerConnection, innerConnection);
        transactionManager = new JdbcTransactionManager(dataSource);
    }

    @AfterEach
    void tearDown() {
        ConnectionHolder.clear();
    }

    // ─── Жизненный цикл транзакции ────────────────────────────────────────────

    @Test
    void execute_shouldCommitAndReturnResult() throws SQLException {
        // when
        String result = transactionManager.execute(() -> "ok");

        // then
        assertThat(result).isEqualTo("ok");
        verify(outerConnection).setAutoCommit(false);
        verify(outerConnection).commit();
        verify(outerConnection, never()).rollback();
        verify(outerConnection).close();
    }

    @Test
    void execute_shouldBindConnectionDuringAction() {
        // when
        Connection seen = transactionManager.execute(() -> ConnectionHolder.require());

        // then
        assertThat(seen).isSameAs(outerConnection);
    }

    @Test
    void execute_shouldClearConnectionHolder_afterCommit() {
        // when
        transactionManager.execute(() -> "ok");

        // then
        assertThat(ConnectionHolder.isActive()).isFalse();
    }

    @Test
    void execute_shouldRollbackAndRethrowSameException_whenActionFails() throws SQLException {
        // given
        IllegalStateException failure = new IllegalStateException("boom");

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(() -> { throw failure; }))
                .isSameAs(failure);
        verify(outerConnection).rollback();
        verify(outerConnection, never()).commit();
        verify(outerConnection).close();
        assertThat(ConnectionHolder.isActive()).isFalse();
    }

    @Test
    void execute_shouldRunRunnableInTransaction() throws SQLException {
        // given
        AtomicBoolean ran = new AtomicBoolean(false);

        // when
        transactionManager.execute(() -> ran.set(true));

        // then
        assertThat(ran).isTrue();
        verify(outerConnection).commit();
    }

    @Test
    void execute_shouldUseReadCommittedByDefault() throws SQLException {
        // when
        transactionManager.execute(() -> "ok");

        // then
        verify(outerConnection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    void execute_shouldApplyRequestedIsolationLevel() throws SQLException {
        // when
        transactionManager.execute(Propagation.REQUIRED, IsolationLevel.SERIALIZABLE, () -> "ok");

        // then
        verify(outerConnection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    }

    @Test
    void execute_shouldThrowDatabaseException_whenConnectionCannotBeAcquired() throws SQLException {
        // given
        when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(() -> "ok"))
                .isInstanceOf(DatabaseException.class)
                .hasCauseInstanceOf(SQLException.class);
        assertThat(ConnectionHolder.isActive()).isFalse();
    }

    @Test
    void execute_shouldKeepOriginalException_whenRollbackFails() throws SQLException {
        // given
        IllegalStateException failure = new IllegalStateException("boom");
        org.mockito.Mockito.doThrow(new SQLException("rollback failed")).when(outerConnection).rollback();

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(() -> { throw failure; }))
                .isSameAs(failure);
        verify(outerConnection).close();
    }

    // ─── REQUIRED ─────────────────────────────────────────────────────────────

    @Test
    void required_shouldJoinExistingTransaction() throws SQLException {
        // when
        Connection innerSeen = transactionManager.execute(() ->
                transactionManager.execute(Propagation.REQUIRED, () -> ConnectionHolder.require()));

        // then
        assertThat(innerSeen).isSameAs(outerConnection);
        verify(dataSource, times(1)).getConnection();
        verify(outerConnection, times(1)).commit();
    }

    @Test
    void required_shouldRollbackOuterTransaction_whenInnerFails() throws SQLException {
        // when / then
        assertThatThrownBy(() -> transactionManager.execute(() ->
                transactionManager.execute(Propagation.REQUIRED, () -> { throw new IllegalStateException("inner"); })))
                .hasMessage("inner");
        verify(outerConnection).rollback();
        verify(outerConnection, never()).commit();
    }

    // ─── REQUIRES_NEW ─────────────────────────────────────────────────────────

    @Test
    void requiresNew_shouldUseSeparateConnection_andRestoreOuter() throws SQLException {
        // when
        Connection[] seen = new Connection[2];
        transactionManager.execute(() -> {
            seen[0] = transactionManager.execute(Propagation.REQUIRES_NEW, () -> ConnectionHolder.require());
            seen[1] = ConnectionHolder.require();
            return null;
        });

        // then
        assertThat(seen[0]).isSameAs(innerConnection);
        assertThat(seen[1]).isSameAs(outerConnection);
        verify(innerConnection).commit();
        verify(innerConnection).close();
        verify(outerConnection).commit();
    }

    @Test
    void requiresNew_shouldNotAffectOuter_whenInnerFails() throws SQLException {
        // when
        transactionManager.execute(() -> {
            try {
                transactionManager.execute(Propagation.REQUIRES_NEW, () -> { throw new IllegalStateException("inner"); });
            } catch (IllegalStateException ignored) {
                // внешний сценарий обработал ошибку и продолжает работу
            }
            return null;
        });

        // then
        verify(innerConnection).rollback();
        verify(outerConnection).commit();
        verify(outerConnection, never()).rollback();
    }

    @Test
    void requiresNew_shouldRestoreOuterConnection_whenInnerFails() {
        // when
        Connection[] afterInner = new Connection[1];
        transactionManager.execute(() -> {
            try {
                transactionManager.execute(Propagation.REQUIRES_NEW, () -> { throw new IllegalStateException("inner"); });
            } catch (IllegalStateException ignored) {
            }
            afterInner[0] = ConnectionHolder.require();
            return null;
        });

        // then
        assertThat(afterInner[0]).isSameAs(outerConnection);
    }

    // ─── MANDATORY ────────────────────────────────────────────────────────────

    @Test
    void mandatory_shouldThrow_whenNoActiveTransaction() throws SQLException {
        // when / then
        assertThatThrownBy(() -> transactionManager.execute(Propagation.MANDATORY, () -> "ok"))
                .isInstanceOf(IllegalStateException.class);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void mandatory_shouldRunInsideActiveTransaction() {
        // when
        Connection seen = transactionManager.execute(() ->
                transactionManager.execute(Propagation.MANDATORY, () -> ConnectionHolder.require()));

        // then
        assertThat(seen).isSameAs(outerConnection);
    }

    // ─── SUPPORTS ─────────────────────────────────────────────────────────────

    @Test
    void supports_shouldRunWithoutOpeningTransaction() throws SQLException {
        // when
        String result = transactionManager.execute(Propagation.SUPPORTS, () -> "ok");

        // then
        assertThat(result).isEqualTo("ok");
        verify(dataSource, never()).getConnection();
    }
}
