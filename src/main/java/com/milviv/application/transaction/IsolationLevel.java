package com.milviv.application.transaction;

import java.sql.Connection;

/**
 * Уровни изоляции транзакций.
 * Обёртка над константами java.sql.Connection — чтобы не светить JDBC в application layer.
 *
 * READ_COMMITTED — стандарт для большинства операций (настроен в HikariCP).
 * REPEATABLE_READ — для операций с повторным чтением одних данных.
 * SERIALIZABLE — только для финансово-критичных операций, высокая цена производительности.
 */
public enum IsolationLevel {

  READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
  REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
  SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

  private final int jdbcLevel;

  IsolationLevel(int jdbcLevel) {
    this.jdbcLevel = jdbcLevel;
  }

  public int  getJdbcLevel() {
    return jdbcLevel;
  }
}
