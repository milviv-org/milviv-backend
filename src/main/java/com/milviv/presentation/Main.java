package com.milviv.presentation;

import java.net.ConnectException;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.application.auth.AccessTokenIssuer;
import com.milviv.application.auth.AuthPolicy;
import com.milviv.application.auth.LoginAttemptLimiter;
import com.milviv.application.auth.LoginUser;
import com.milviv.application.auth.Logout;
import com.milviv.application.auth.PasswordHasher;
import com.milviv.application.auth.RefreshSession;
import com.milviv.application.auth.RegisterUser;
import com.milviv.application.auth.SessionIssuer;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.application.gamification.HiddenRewardsAccess;
import com.milviv.application.gamification.PointsQueries;
import com.milviv.application.gamification.RewardCommands;
import com.milviv.application.gamification.SetHiddenRewardsPassword;
import com.milviv.application.goal.GoalCommands;
import com.milviv.application.planning.CalendarPolicy;
import com.milviv.application.planning.CalendarQuery;
import com.milviv.application.planning.PlanCommands;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.reflection.ReflectionPolicy;
import com.milviv.application.reflection.ReflectionQuestionCommands;
import com.milviv.application.reflection.TaskReflectionCommands;
import com.milviv.application.statistics.StatisticsPolicy;
import com.milviv.application.statistics.StatisticsQueries;
import com.milviv.application.task.CompleteTask;
import com.milviv.application.task.TaskCommands;
import com.milviv.application.user.GetCurrentUser;
import com.milviv.domain.auth.RefreshTokenRepository;
import com.milviv.domain.gamification.HiddenRewardsPasswordRepository;
import com.milviv.domain.gamification.PointsHistoryRepository;
import com.milviv.domain.gamification.RewardRepository;
import com.milviv.domain.goal.GoalRepository;
import com.milviv.domain.planning.PlanRepository;
import com.milviv.domain.planning.TaskRescheduleRepository;
import com.milviv.domain.project.ProjectRepository;
import com.milviv.domain.reflection.ReflectionAnswerRepository;
import com.milviv.domain.reflection.ReflectionQuestionRepository;
import com.milviv.domain.reflection.TaskReflectionRepository;
import com.milviv.domain.statistics.StatisticsRepository;
import com.milviv.domain.task.TaskCompletionRepository;
import com.milviv.domain.task.TaskRepository;
import com.milviv.infrastructure.config.AppConfig;
import com.milviv.infrastructure.config.AuthConfig;
import com.milviv.infrastructure.config.CalendarConfig;
import com.milviv.infrastructure.config.ReflectionConfig;
import com.milviv.infrastructure.config.StatisticsConfig;
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
import com.milviv.infrastructure.persistence.jdbc.GoalJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.HiddenRewardsPasswordJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PlanJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PointsHistoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ProjectJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ReflectionAnswerJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.ReflectionQuestionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.RewardJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.StatisticsJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.RefreshTokenJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskCompletionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskReflectionJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.TaskRescheduleJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.UserJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.infrastructure.security.AuthCookies;
import com.milviv.infrastructure.security.BCryptPasswordHasher;
import com.milviv.infrastructure.security.InMemoryLoginAttemptLimiter;
import com.milviv.infrastructure.security.JwtAccessTokenService;
import com.milviv.infrastructure.security.JwtCookieAuthenticator;
import com.milviv.presentation.rest.controllers.AuthController;
import com.milviv.presentation.rest.controllers.CalendarController;
import com.milviv.presentation.rest.controllers.GoalController;
import com.milviv.presentation.rest.controllers.HealthController;
import com.milviv.presentation.rest.controllers.PlanController;
import com.milviv.presentation.rest.controllers.PointsController;
import com.milviv.presentation.rest.controllers.ReflectionController;
import com.milviv.presentation.rest.controllers.RewardController;
import com.milviv.presentation.rest.controllers.StatisticsController;
import com.milviv.presentation.rest.controllers.ProjectController;
import com.milviv.presentation.rest.controllers.TaskController;
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
                    .register(AuthConfig.class, Scope.SINGLETON)
                    .register(CalendarConfig.class, Scope.SINGLETON)
                    .register(ReflectionConfig.class, Scope.SINGLETON)
                    .register(StatisticsConfig.class, Scope.SINGLETON)
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
            container.singletone(RefreshTokenRepository.class, new RefreshTokenJdbcRepository(connectionProvider));

            // Аутентификация
            AuthConfig authConfig = container.get(AuthConfig.class);
            Clock clock = Clock.systemUTC();
            JwtAccessTokenService jwtService = new JwtAccessTokenService(
                    authConfig.getJwtSecret(), authConfig.getJwtIssuer(), authConfig.getAccessTokenTtl());
            AuthCookies authCookies = new AuthCookies(authConfig.isCookieSecure());

            container.singletone(Clock.class, clock);
            container.singletone(AuthPolicy.class, authConfig.toPolicy());
            container.singletone(AccessTokenIssuer.class, jwtService);
            container.singletone(PasswordHasher.class, new BCryptPasswordHasher(authConfig.getBcryptCost()));
            container.singletone(LoginAttemptLimiter.class, new InMemoryLoginAttemptLimiter(
                    authConfig.getLoginMaxAttempts(), authConfig.getLoginWindow(), clock));
            container.singletone(AuthCookies.class, authCookies);
            container
                    .register(SessionIssuer.class)
                    .register(RegisterUser.class)
                    .register(LoginUser.class)
                    .register(RefreshSession.class)
                    .register(Logout.class)
                    .register(GetCurrentUser.class);

            // Цели, проекты, задачи, рефлексия
            container.singletone(ReflectionQuestionRepository.class, new ReflectionQuestionJdbcRepository(connectionProvider));
            container.singletone(TaskReflectionRepository.class, new TaskReflectionJdbcRepository(connectionProvider));
            container.singletone(ReflectionAnswerRepository.class, new ReflectionAnswerJdbcRepository(connectionProvider));
            container.singletone(ReflectionPolicy.class, container.get(ReflectionConfig.class).toPolicy());
            container.singletone(GoalRepository.class, new GoalJdbcRepository(connectionProvider));
            container.singletone(ProjectRepository.class, new ProjectJdbcRepository(connectionProvider));
            container.singletone(TaskRepository.class, new TaskJdbcRepository(connectionProvider));
            container.singletone(TaskCompletionRepository.class, new TaskCompletionJdbcRepository(connectionProvider));
            container.singletone(PointsHistoryRepository.class, new PointsHistoryJdbcRepository(connectionProvider));
            container.singletone(PlanRepository.class, new PlanJdbcRepository(connectionProvider));
            container.singletone(TaskRescheduleRepository.class, new TaskRescheduleJdbcRepository(connectionProvider));
            container.singletone(CalendarPolicy.class, container.get(CalendarConfig.class).toPolicy());
            container
                    .register(GoalCommands.class)
                    .register(ProjectCommands.class)
                    .register(TaskCommands.class)
                    .register(CompleteTask.class)
                    .register(ReflectionQuestionCommands.class)
                    .register(TaskReflectionCommands.class)
                    .register(PlanCommands.class)
                    .register(CalendarQuery.class);

            // Награды и баллы
            container.singletone(RewardRepository.class, new RewardJdbcRepository(connectionProvider));
            container.singletone(HiddenRewardsPasswordRepository.class, new HiddenRewardsPasswordJdbcRepository(connectionProvider));
            container
                    .register(HiddenRewardsAccess.class)
                    .register(RewardCommands.class)
                    .register(PointsQueries.class)
                    .register(SetHiddenRewardsPassword.class);

            // Статистика
            container.singletone(StatisticsRepository.class, new StatisticsJdbcRepository(connectionProvider));
            container.singletone(StatisticsPolicy.class, container.get(StatisticsConfig.class).toPolicy());
            container.register(StatisticsQueries.class);

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
            RouterScanner routerScanner = new RouterScanner(router, new JwtCookieAuthenticator(jwtService, authCookies, clock));
            ControllerRegistrar contollerRegistrar = new ControllerRegistrar(container, routerScanner);
            contollerRegistrar.register(
                    AuthController.class,
                    GoalController.class,
                    ProjectController.class,
                    TaskController.class,
                    PlanController.class,
                    CalendarController.class,
                    RewardController.class,
                    PointsController.class,
                    ReflectionController.class,
                    StatisticsController.class,
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
