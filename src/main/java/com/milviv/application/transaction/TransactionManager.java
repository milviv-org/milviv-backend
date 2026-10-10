package com.milviv.application.transaction;

import java.util.function.Supplier;

/**
 * Контракт управления транзакциями для Application Layer.
 *
 * Application Services используют этот интерфейс — не знают про JDBC.
 * Реализация живёт в Infrastructure Layer.
 *
 * Все перегрузки делегируют в execute(Propagation, IsolationLevel, Supplier<T>).
 */
public interface TransactionManager {
  
  /** Выполнить действие в транзакции. При исключении — ROLLBACK. */
  void execute(Runnable action);

    /** Выполнить действие в транзакции и вернуть результат. */
  <T> T execute(Supplier<T> action);

    /** Выполнить с указанным типом propagation. */
  <T> T execute(Propagation propagation, Supplier<T> action);

    /** Полный контроль: propagation + isolation level. */
  <T> T execute(Propagation propagation, IsolationLevel isolation, Supplier<T> action);
}
