package com.milviv.unit.infrastructure.persistence.transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milviv.application.transaction.IsolationLevel;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionHolder;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

class RetryableTransactionManagerTest {

    private static final int MAX_ATTEMPTS = 3;

    private TransactionManager delegate;
    private RetryableTransactionManager transactionManager;
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        delegate = mock(TransactionManager.class);
        transactionManager = new RetryableTransactionManager(delegate, MAX_ATTEMPTS, 1, 1);
        calls = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        ConnectionHolder.clear();
        Thread.interrupted(); // сбросить флаг, если тест его выставил
    }

    /** Делегат выполняет действие, как настоящий менеджер транзакций, и считает вызовы. */
    @SuppressWarnings("unchecked")
    private void delegateRunsAction() {
        when(delegate.execute(any(Propagation.class), any(IsolationLevel.class), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    return ((Supplier<Object>) invocation.getArgument(2)).get();
                });
    }

    /** Действие, которое падает первые {@code failures} раз, затем возвращает "ok". */
    private Supplier<String> failingTimes(int failures, Supplier<RuntimeException> error) {
        AtomicInteger attempts = new AtomicInteger();
        return () -> {
            if (attempts.incrementAndGet() <= failures) {
                throw error.get();
            }
            return "ok";
        };
    }

    private static OptimisticLockException optimisticLock() {
        return new OptimisticLockException("User", UUID.randomUUID(), 1);
    }

    private static DatabaseException sqlFailure(String sqlState) {
        return new DatabaseException("Failed to update", new SQLException("db error", sqlState));
    }

    // ─── Успех и повторы ──────────────────────────────────────────────────────

    @Test
    void execute_shouldNotRetry_whenActionSucceeds() {
        // given
        delegateRunsAction();

        // when
        String result = transactionManager.execute(() -> "ok");

        // then
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void execute_shouldRetryOptimisticLock_untilSuccess() {
        // given
        delegateRunsAction();

        // when
        String result = transactionManager.execute(failingTimes(2, RetryableTransactionManagerTest::optimisticLock));

        // then
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void execute_shouldGiveUp_afterMaxAttempts() {
        // given
        delegateRunsAction();

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(failingTimes(10, RetryableTransactionManagerTest::optimisticLock)))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(calls).hasValue(MAX_ATTEMPTS);
    }

    @Test
    void execute_shouldRetry_whenSerializationFailure() {
        // given
        delegateRunsAction();

        // when
        String result = transactionManager.execute(failingTimes(1, () -> sqlFailure("40001")));

        // then
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void execute_shouldRetry_whenDeadlock() {
        // given
        delegateRunsAction();

        // when
        String result = transactionManager.execute(failingTimes(1, () -> sqlFailure("40P01")));

        // then
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void execute_shouldRetry_whenSqlStateIsDeepInCauseChain() {
        // given
        delegateRunsAction();
        Supplier<RuntimeException> nested = () ->
                new IllegalStateException("wrapper", sqlFailure("40001"));

        // when
        String result = transactionManager.execute(failingTimes(1, nested));

        // then
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void execute_shouldNotRetry_whenOtherSqlError() {
        // given
        delegateRunsAction();

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(failingTimes(1, () -> sqlFailure("23505"))))
                .isInstanceOf(DatabaseException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void execute_shouldNotRetry_whenBusinessError() {
        // given
        delegateRunsAction();

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(failingTimes(1, () -> new IllegalArgumentException("bad"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void execute_shouldPassPropagationAndIsolationToDelegate() {
        // given
        delegateRunsAction();

        // when
        transactionManager.execute(Propagation.REQUIRES_NEW, IsolationLevel.SERIALIZABLE, () -> "ok");

        // then
        verify(delegate).execute(eq(Propagation.REQUIRES_NEW), eq(IsolationLevel.SERIALIZABLE), any());
    }

    // ─── Повтор только на внешнем уровне ──────────────────────────────────────

    @Test
    void execute_shouldNotRetry_whenJoiningActiveTransaction() {
        // given
        delegateRunsAction();
        ConnectionHolder.bind(mock(Connection.class));

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(Propagation.REQUIRED,
                failingTimes(1, RetryableTransactionManagerTest::optimisticLock)))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void execute_shouldRetry_whenRequiresNewInsideActiveTransaction() {
        // given
        delegateRunsAction();
        ConnectionHolder.bind(mock(Connection.class));

        // when
        String result = transactionManager.execute(Propagation.REQUIRES_NEW,
                failingTimes(1, RetryableTransactionManagerTest::optimisticLock));

        // then
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void execute_shouldNotRetry_whenMandatory() {
        // given
        delegateRunsAction();
        ConnectionHolder.bind(mock(Connection.class));

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(Propagation.MANDATORY,
                failingTimes(1, RetryableTransactionManagerTest::optimisticLock)))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void execute_shouldRetry_whenSupportsOpensOwnTransaction() {
        // given
        delegateRunsAction();

        // when
        String result = transactionManager.execute(Propagation.SUPPORTS,
                failingTimes(1, RetryableTransactionManagerTest::optimisticLock));

        // then
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void execute_shouldNotRetry_whenSupportsJoinsActiveTransaction() {
        // given
        delegateRunsAction();
        ConnectionHolder.bind(mock(Connection.class));

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(Propagation.SUPPORTS,
                failingTimes(1, RetryableTransactionManagerTest::optimisticLock)))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(calls).hasValue(1);
    }

    // ─── Прерывание и конструктор ─────────────────────────────────────────────

    @Test
    void execute_shouldStopAndKeepInterruptFlag_whenInterruptedDuringBackoff() {
        // given
        delegateRunsAction();
        Thread.currentThread().interrupt();

        // when / then
        assertThatThrownBy(() -> transactionManager.execute(failingTimes(1, RetryableTransactionManagerTest::optimisticLock)))
                .isInstanceOf(DatabaseException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void constructor_shouldReject_whenMaxRetriesLessThanOne() {
        assertThatThrownBy(() -> new RetryableTransactionManager(delegate, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldReject_whenDelaysInvalid() {
        assertThatThrownBy(() -> new RetryableTransactionManager(delegate, 3, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryableTransactionManager(delegate, 3, 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
