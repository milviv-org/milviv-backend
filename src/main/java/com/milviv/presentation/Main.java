package com.milviv.presentation;

import java.net.ConnectException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.application.transaction.TransactionManager;
import com.milviv.infrastructure.config.AppConfig;
import com.milviv.infrastructure.config.ConfigLoader;
import com.milviv.infrastructure.config.ControllerRegistrar;
import com.milviv.infrastructure.config.JacksonConfig;
import com.milviv.infrastructure.container.DIContainer;
import com.milviv.infrastructure.container.Scope;
import com.milviv.infrastructure.http.ErrorMapper;
import com.milviv.infrastructure.http.HttpContextAnnotationValidator;
import com.milviv.infrastructure.http.HttpStatusResolver;
import com.milviv.infrastructure.http.JettyServer;
import com.milviv.infrastructure.http.router.Router;
import com.milviv.infrastructure.http.router.RouterScanner;
import com.milviv.infrastructure.migration.FlywayMigrationRunner;
import com.milviv.infrastructure.persistence.DatabaseConnectionManager;
import com.milviv.domain.user.UserRepository;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.UserJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.presentation.rest.controllers.AuthController;
import com.milviv.presentation.rest.controllers.HealthController;
import com.milviv.presentation.rest.controllers.UserContextController;
import com.milviv.shared.validation.annotation.AnnotationValidator;

/**
 * Main entry point for MILVIV application.
 *
 * Composition Root — единственное место где собирается граф зависимостей.
 * Только здесь знают какие реализации используются.
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            logger.info("Starting MILVIV application...");

            // ═══════════════════════════════════════════════════════════
            // ФАЗА 1: Регистрация — описываем граф, объекты ещё не созданы
            // ═══════════════════════════════════════════════════════════
            DIContainer container = new DIContainer();

            container
                    .register(ConfigLoader.class, Scope.SINGLETON)
                    .register(AppConfig.class, Scope.SINGLETON)
                    .register(DatabaseConnectionManager.class, Scope.SINGLETON)
                    .register(FlywayMigrationRunner.class, Scope.SINGLETON)
                    .register(JacksonConfig.class, Scope.SINGLETON);

            // TODO: по мере роста проекта добавлять сюда:
            // ═══════════════════════════════════════════════════════════
            // ФАЗА 2: Инициализация — контейнер рекурсивно создаёт весь граф
            // ═══════════════════════════════════════════════════════════
            AppConfig config = container.get(AppConfig.class);
            logger.info("Configuration loaded: {}", config);

            logger.info("Initializing database connection pool...");
            DatabaseConnectionManager dbManager = container.get(DatabaseConnectionManager.class);

            TransactionalConnectionProvider connectionProvider = new TransactionalConnectionProvider();
            JdbcTransactionManager jdbcTransactionManager = new JdbcTransactionManager(dbManager.getDataSource());
            RetryableTransactionManager transactionManager = new RetryableTransactionManager(jdbcTransactionManager, 3);

            container.singletone(ConnectionProvider.class, connectionProvider);
            container.singletone(TransactionManager.class, transactionManager);
            container.singletone(UserRepository.class, new UserJdbcRepository(connectionProvider));

            // ═══════════════════════════════════════════════════════════
            // ФАЗА 3: Проверки и запуск
            // ═══════════════════════════════════════════════════════════
            if (!dbManager.isHealthy()) {
                logger.error("Database health check failed! Cannot start application.");
                System.exit(1);
            }
            logger.info("Database connection successful!");

            DatabaseConnectionManager.PoolStats stats = dbManager.getPoolStats();
            logger.info("Connection pool initialized: {}", stats);

            FlywayMigrationRunner flywayMigrationRunner = container.get(FlywayMigrationRunner.class);
            flywayMigrationRunner.migrate();

            HttpStatusResolver httpStatusResolver = new HttpStatusResolver();
            ErrorMapper errorMapper = new ErrorMapper(httpStatusResolver);

            JacksonConfig jacksonConfig = container.get(JacksonConfig.class);
            Router router = new Router(
                errorMapper, 
                jacksonConfig.getObjectMapper(), 
                new HttpContextAnnotationValidator(new AnnotationValidator())
            );
            RouterScanner routerScanner = new RouterScanner(router);
            ControllerRegistrar contollerRegistrar = new ControllerRegistrar(container, routerScanner);
            contollerRegistrar.register(
                    AuthController.class,
                    UserContextController.class,
                    HealthController.class
            );

            JettyServer server = new JettyServer(config, router);
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                } catch (Exception e) {
                    logger.error("Error stopping server", e);
                } finally {
                    dbManager.shutdown();
                }
            }));

            logger.info("MILVIV {} started successfully in {} mode",
                    config.getAppVersion(),
                    config.getAppEnvironment());

            logger.info("Application is running. Press Ctrl+C to stop.");
            Thread.currentThread().join();

        } catch (Exception e) {
            logger.error("Failed to start application", e);
            System.exit(1);
        }
    }
}
