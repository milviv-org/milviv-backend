package com.milviv.infrastructure.persistence.connection;

import java.sql.Connection;

/**
 * Абстракция получения Connection для репозиториев.
 *
 * Репозитории зависят от этого интерфейса, не от ThreadLocal напрямую.
 * В тестах подменяется лямбдой: () -> testConnection
 */
@FunctionalInterface
public interface ConnectionProvider {
    Connection get();
}
