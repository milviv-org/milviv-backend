package com.milviv.infrastructure.config;

import com.milviv.infrastructure.container.Inject;

/**
 * Application configuration holder.
 * Loads configuration from ConfigLoader and provides typed access to settings.
 */
public class AppConfig {
    private final ConfigLoader configLoader;

    // Database settings
    private final String databaseUrl;
    private final String databaseUsername;
    private final String databasePassword;
    private final int databasePoolSize;
    private final int databaseConnectionTimeout;

    // Server settings
    private final int serverPort;
    private final String serverHost;

    // Application settings
    private final String appName;
    private final String appVersion;
    private final String appEnvironment;

    // Logging settings
    private final String loggingLevel;
    private final String loggingFilePath;

    public AppConfig() {
        this(new ConfigLoader());
    }

    @Inject
    public AppConfig(ConfigLoader configLoader) {
        this.configLoader = configLoader;

        // Load database configuration
        this.databaseUrl = configLoader.get("DB_URL", "db.url");
        this.databaseUsername = configLoader.get("DB_USERNAME", "db.username");
        this.databasePassword = configLoader.get("DB_PASSWORD", "db.password");
        this.databasePoolSize = configLoader.getInt("DB_POOL_SIZE", "db.pool.size", 10);
        this.databaseConnectionTimeout = configLoader.getInt("DB_CONNECTION_TIMEOUT", "db.connection.timeout", 30000);

        // Load server configuration
        this.serverPort = configLoader.getInt("SERVER_PORT", "server.port", 8080);
        this.serverHost = configLoader.get("SERVER_HOST", "server.host", "0.0.0.0");

        // Load application configuration
        this.appName = configLoader.get("APP_NAME", "app.name", "MILVIV");
        this.appVersion = configLoader.get("APP_VERSION", "app.version", "1.0.0");
        this.appEnvironment = configLoader.get("APP_ENVIRONMENT", "app.environment", "development");

        // Load logging configuration
        this.loggingLevel = configLoader.get("LOGGING_LEVEL", "logging.level", "INFO");
        this.loggingFilePath = configLoader.get("LOGGING_FILE_PATH", "logging.file.path", "logs/application.log");

        validate();
    }

    /**
     * Validate required configuration values.
     */
    private void validate() {
        if (databaseUrl == null || databaseUrl.isEmpty()) {
            throw new IllegalStateException("Database URL is required (DB_URL or db.url)");
        }
        if (databaseUsername == null || databaseUsername.isEmpty()) {
            throw new IllegalStateException("Database username is required (DB_USERNAME or db.username)");
        }
        if (databasePassword == null || databasePassword.isEmpty()) {
            throw new IllegalStateException("Database password is required (DB_PASSWORD or db.password)");
        }
    }

    // Database getters
    public String getDatabaseUrl() {
        return databaseUrl;
    }

    public String getDatabaseUsername() {
        return databaseUsername;
    }

    public String getDatabasePassword() {
        return databasePassword;
    }

    public int getDatabasePoolSize() {
        return databasePoolSize;
    }

    public int getDatabaseConnectionTimeout() {
        return databaseConnectionTimeout;
    }

    // Server getters
    public int getServerPort() {
        return serverPort;
    }

    public String getServerHost() {
        return serverHost;
    }

    // Application getters
    public String getAppName() {
        return appName;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getAppEnvironment() {
        return appEnvironment;
    }

    public boolean isDevelopment() {
        return "development".equalsIgnoreCase(appEnvironment);
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(appEnvironment);
    }

    // Logging getters
    public String getLoggingLevel() {
        return loggingLevel;
    }

    public String getLoggingFilePath() {
        return loggingFilePath;
    }

    @Override
    public String toString() {
        return "AppConfig{" +
                "databaseUrl='" + databaseUrl + '\'' +
                ", databaseUsername='" + databaseUsername + '\'' +
                ", databasePassword='***'" +
                ", databasePoolSize=" + databasePoolSize +
                ", databaseConnectionTimeout=" + databaseConnectionTimeout +
                ", serverPort=" + serverPort +
                ", serverHost='" + serverHost + '\'' +
                ", appName='" + appName + '\'' +
                ", appVersion='" + appVersion + '\'' +
                ", appEnvironment='" + appEnvironment + '\'' +
                ", loggingLevel='" + loggingLevel + '\'' +
                ", loggingFilePath='" + loggingFilePath + '\'' +
                '}';
    }
}
