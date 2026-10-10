package com.milviv.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.infrastructure.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class DatabaseConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnectionManager.class);

    private final HikariDataSource dataSource;
    private final AppConfig config;

    public DatabaseConnectionManager(AppConfig config) {
        this.config = config;
        this.dataSource = initializeDataSource();
        logger.info("DataConnectionManager initialized with pool size {}", config.getDatabasePoolSize());
    }

    private HikariDataSource initializeDataSource() {
        try {
            HikariConfig hikariConfig = new HikariConfig();

            // JDBC settings
            hikariConfig.setJdbcUrl(config.getDatabaseUrl());
            hikariConfig.setUsername(config.getDatabaseUsername());
            hikariConfig.setPassword(config.getDatabasePassword());

            // Pool settings
            hikariConfig.setMaximumPoolSize(config.getDatabasePoolSize());
            hikariConfig.setMinimumIdle(Math.max(2, config.getDatabasePoolSize() / 2));
            hikariConfig.setConnectionTimeout(config.getDatabaseConnectionTimeout());

            int min = 1000 * 60; // 1 минута в мс

            // Connection lifecycle
            hikariConfig.setIdleTimeout(min * 10); // 10мин
            hikariConfig.setMaxLifetime(min * 30); // 30мин
            hikariConfig.setKeepaliveTime(min * 5); // 5мин

            // Poolname для мониторинга(пока нигде не мониторится)
            hikariConfig.setPoolName("MILVIVPool");

            // Connection test query
            hikariConfig.setConnectionTestQuery("SELECT 1");

            // Оптимизация и консистеностность данных
            hikariConfig.setAutoCommit(true);
            hikariConfig.setTransactionIsolation("TRANSACTION_READ_COMMITTED");

            // Leak detection (for development)
            if (config.isDevelopment()) {
                hikariConfig.setLeakDetectionThreshold(10000); // 10 seconds
            }

            logger.info("Initializing HikariCP with URL: {}", config.getDatabaseUrl());

            return new HikariDataSource(hikariConfig);
        } catch (Exception e) {
            logger.error("Failed to initialize database connection pool", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public Connection getConnection() throws SQLException {
        try {
            Connection connection = this.dataSource.getConnection();
            return connection;
        } catch (Exception e) {
            logger.error(
                    "Failed to get connection from pool", e
            );
            throw e;
        }
    }

    public DataSource getDataSource() {
        return this.dataSource;
    }

    public boolean isHealthy() {
        try (Connection connection = this.getConnection()) {
            return connection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    public PoolStats getPoolStats() {
        return new PoolStats(
                this.dataSource.getHikariPoolMXBean().getTotalConnections(),
                this.dataSource.getHikariPoolMXBean().getActiveConnections(),
                this.dataSource.getHikariPoolMXBean().getIdleConnections(),
                this.dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()
        );
    }

    public record PoolStats(
            int total,
            int active,
            int idle,
            int waiting
            ) {

        @Override
        public String toString() {
            return String.format("Pool[total=%d, active=%d, idle=%d, waiting=%d]", total, active, idle, waiting);
        }
    }

    public void shutdown() {
        if (this.dataSource != null && !this.dataSource.isClosed()) {
            logger.info("Shutting down database connection pool...");
            this.dataSource.close();
            logger.info("Database connection pool closed");
        }
    }

    public boolean isClosed() {
        return this.dataSource == null || this.dataSource.isClosed();
    }
}
