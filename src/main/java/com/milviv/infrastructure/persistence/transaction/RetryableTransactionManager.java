package com.milviv.infrastructure.persistence.transaction;

import com.milviv.application.transaction.IsolationLevel;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionHolder;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RetryableTransactionManager implements TransactionManager {
  private static final Logger logger = LoggerFactory.getLogger(RetryableTransactionManager.class);

  private static final String DEADLOCK_SQL_STATE = "40P01";
  private static final String SERIALIZATION_FAILURE_SQL_STATE = "40001";

  private static final long DEFAULT_BASE_DELAY_MS = 50;
  private static final long DEFAULT_MAX_DELAY_MS = 1000;

  private final TransactionManager delegate;
  private final int maxRetries;
  private final long baseDelayMs;
  private final long maxDelayMs;

  public RetryableTransactionManager(TransactionManager delegate, int maxRetries) {
    this(delegate, maxRetries, DEFAULT_BASE_DELAY_MS, DEFAULT_MAX_DELAY_MS);
  }

  /**
   * @param baseDelayMs базовая пауза перед первым повтором;
   *                    с каждой попыткой удваивается (exponential backoff)
   * @param maxDelayMs  потолок паузы — экспонента не растёт бесконечно
   */
  public RetryableTransactionManager(TransactionManager delegate, int maxRetries,
                                     long baseDelayMs, long maxDelayMs) {
    if (maxRetries < 1) {
      throw new IllegalArgumentException("maxRetries must be >= 1");
    }
    if (baseDelayMs < 1 || maxDelayMs < baseDelayMs) {
      throw new IllegalArgumentException("Delays must satisfy: 1 <= baseDelayMs <= maxDelayMs");
    }

    this.delegate = delegate;
    this.maxRetries = maxRetries;
    this.baseDelayMs = baseDelayMs;
    this.maxDelayMs = maxDelayMs;
  }

  @Override
  public void execute(Runnable action) {
    execute(Propagation.REQUIRED, IsolationLevel.READ_COMMITTED, () -> {
      action.run();
      return null;
    });
  }

  @Override
  public <T> T execute(Supplier<T> action) {
    return execute(Propagation.REQUIRED, IsolationLevel.READ_COMMITTED, action);
  }

  @Override
  public <T> T execute(Propagation propagation, Supplier<T> action) {
    return execute(propagation, IsolationLevel.READ_COMMITTED, action);
  }

  @Override
  public <T> T execute(Propagation propagation, IsolationLevel isolation, Supplier<T> action) {
    // Повторять можно только транзакцию целиком. Вызов, присоединившийся к внешней
    // транзакции, повторять нельзя: записи первой попытки уже в ней, а после ошибки
    // PostgreSQL транзакцию прерывает. Повтор выполнит внешний уровень.
    if (!opensNewTransaction(propagation)) {
      return delegate.execute(propagation, isolation, action);
    }

    int attempt = 0;
    while (true) {
      try {
        return delegate.execute(propagation, isolation, action);
      } catch(OptimisticLockException ex) {
        attempt++;
        if (attempt >= maxRetries) {
          logger.warn("OptimisticLockException after {} at attempts, giving up", attempt);
          throw ex;
        }
        logger.debug("OptimisticLockException on attempt {}, retrying...", attempt);
        sleepBeforeRetry(attempt);
      } catch (RuntimeException ex) {
        if (isDeadlockOrSerializationFailure(ex)) {
          attempt++;
          if (attempt >= maxRetries) {
            logger.warn("Deadlock/serialization failure after {} attempts, giving up", attempt);
            throw ex;
          }
          logger.warn("Deadlock detected on attempt {}, retrying...", attempt);
          sleepBeforeRetry(attempt);
        } else {
          throw ex;
        }
      }
    }
  }

  /**
   * Пауза перед повтором: exponential backoff + jitter.
   *
   * Backoff: базовая пауза удваивается с каждой попыткой (50 → 100 → 200мс...),
   * давая конфликтующей транзакции время завершиться. Ограничена maxDelayMs.
   *
   * Jitter: фиксированная половина + случайная половина (equal jitter).
   * Разносит повторы конкурирующих транзакций по времени —
   * иначе они, выждав одинаковую паузу, столкнутся снова.
   */
  private void sleepBeforeRetry(int attempt) {
    long exponential = baseDelayMs << Math.min(attempt - 1, 20); // << = *2^n, cap от переполнения
    long capped = Math.min(exponential, maxDelayMs);
    long delay = capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);

    try {
      Thread.sleep(delay);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt(); // восстановить флаг — поток останавливают
      throw new DatabaseException("Transaction retry interrupted", ex);
    }
  }

  /**
   * Открывает ли этот вызов собственную транзакцию.
   * REQUIRED — только если внешней нет; REQUIRES_NEW — всегда;
   * MANDATORY и SUPPORTS своей транзакции не открывают.
   */
  private boolean opensNewTransaction(Propagation propagation) {
    return switch (propagation) {
      case REQUIRED -> !ConnectionHolder.isActive();
      case REQUIRES_NEW -> true;
      case MANDATORY, SUPPORTS -> false;
    };
  }

  private boolean isDeadlockOrSerializationFailure(RuntimeException ex) {
    Throwable cause = ex.getCause();
    while (cause != null) {
      if (cause instanceof java.sql.SQLException sqlEx) {
        String sqlState = sqlEx.getSQLState();
        if (DEADLOCK_SQL_STATE.equals(sqlState) || SERIALIZATION_FAILURE_SQL_STATE.equals(sqlState)) {
          return true;
        }
      }
      cause = cause.getCause();
    }
    return false;
  }

}
