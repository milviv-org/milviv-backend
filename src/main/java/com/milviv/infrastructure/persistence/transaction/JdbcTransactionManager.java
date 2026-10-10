package com.milviv.infrastructure.persistence.transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.application.transaction.IsolationLevel;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionHolder;

public final class JdbcTransactionManager implements TransactionManager {
    private static final Logger logger = LoggerFactory.getLogger(JdbcTransactionManager.class);

    private final DataSource dataSource;

    public JdbcTransactionManager(DataSource dataSource) {
      this.dataSource = dataSource;
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
      return switch (propagation) {
        case REQUIRED -> executeRequired(isolation, action);
        case REQUIRES_NEW -> executeRequiresNew(isolation, action);
        case MANDATORY -> executeMandatory(action);
        case SUPPORTS -> executeSupports(action);
      };
    }

     // -------------------------------------------------------------------------
    // Propagation implementations
    // -------------------------------------------------------------------------

    /**
     * Присоединяется к текущей транзакции если она есть.
     * Иначе — открывает новую.
     */
    private <T> T executeRequired(IsolationLevel isolation, Supplier<T> action) {
      if (ConnectionHolder.isActive()) {
        return action.get();
      }
      return runInNewTransaction(isolation, action);
    }

    /**
     * Всегда открывает новую транзакцию.
     * Текущую сохраняет и восстанавливает после завершения.
     */
    private <T> T executeRequiresNew(IsolationLevel isolation, Supplier<T> action) {
      Connection suspended = ConnectionHolder.current().orElse(null);
      if (suspended != null) {
        ConnectionHolder.clear();
      }
      try {
        return runInNewTransaction(isolation, action);
      } finally {
        if (suspended != null) {
          ConnectionHolder.bind(suspended);
        }
      }
    }

    /**
     * Требует наличия активной транзакции.
     */
    private <T> T executeMandatory(Supplier<T> action) {
      if (!ConnectionHolder.isActive()) {
        throw new IllegalStateException(
          "Propagation.MANDATORY requires an active transaction, but none found."
        );
      }
      return action.get();
    }

    /**
     * Использует транзакцию если есть, без неё тоже работает.
     */
    private <T> T executeSupports(Supplier<T> action) {
      return action.get();
    }

    // -------------------------------------------------------------------------
    // Core transaction lifecycle
    // -------------------------------------------------------------------------

    private <T> T runInNewTransaction(IsolationLevel isolation, Supplier<T> action) {
      Connection connection = acquireConnection(isolation);
      try {
        T result = action.get();
        connection.commit();
        logger.debug("Transaction committed");
        return result;
      } catch(Exception ex) {
        rollbackQuietly(connection);
        throw wrapIfChecked(ex);
      } finally {
        ConnectionHolder.clear();
        releaseConnection(connection);
      }
    }

    private Connection acquireConnection(IsolationLevel isolation) {
      try {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(isolation.getJdbcLevel());
        ConnectionHolder.bind(connection);
        return connection;
      } catch (SQLException ex) {
        throw new DatabaseException("Failed to acquire connection for transaction", ex);
      }
    }

    private void rollbackQuietly(Connection connection) {
      try {
        connection.rollback();
        logger.debug("Transaction rolled back");
      } catch(SQLException ex) {
        // Логируем — но не перебиваем оригинальное исключение
        logger.error("Faild to rollback transaction", ex);
      } 
    }

    private void releaseConnection(Connection connection) {
      try {
        connection.close(); // HikariCP возвращает в пул
      } catch(SQLException ex) {
        logger.error("Failed to release connection back to pool", ex);
      }
    }

    private RuntimeException wrapIfChecked(Exception ex) {
      if (ex instanceof RuntimeException runtimeEx) {
        return runtimeEx;
      }
      return new DatabaseException("Transaction failed with checked exception", ex);
    }

}
