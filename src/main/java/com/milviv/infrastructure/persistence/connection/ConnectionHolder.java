package com.milviv.infrastructure.persistence.connection;

import java.sql.Connection;
import java.util.Optional;

/**
 * Хранит Connection текущего потока.
 *
 * Единственное место в проекте, где живёт ThreadLocal<Connection>. Все
 * остальные классы работают через ConnectionProvider.
 *
 * КРИТИЧНО: clear() обязателен в finally блоке. Если не вызвать — Connection
 * вернётся в HikariCP с autoCommit=false и открытой транзакцией, что испортит
 * следующего пользователя этого соединения.
 */
public final class ConnectionHolder {

    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

    public static void bind(Connection connection) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException(
                    "Connection already bound to current thread. Use Propagation.REQUIRES_NEW explicitly."
            );
        }
        CURRENT.set(connection);
    }

    /**
     * Возвращает Connection или бросает исключение. Репозиторий, вызванный вне
     * транзакции, сразу даёт понятную ошибку.
     */
    public static Connection require() {
        Connection connection = CURRENT.get();
        if (connection == null) {
            throw new IllegalStateException(
                    "No active transaction on current thread. " + "All repository calls must be wrapped in TransactionManager.execute()"
            );
        }
        return connection;
    }

    public static Optional<Connection> current() {
      return Optional.ofNullable(CURRENT.get());
    }

    public static boolean isActive() {
      return CURRENT.get() != null;
    }

    /** Всегда вызывать в finally. */
    public static void clear() {
      CURRENT.remove();
    }

}
