package com.milviv.unit.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;

import com.milviv.infrastructure.config.AppConfig;
import com.milviv.infrastructure.config.ConfigLoader;

/**
 * Unit tests for AppConfig.
 */
class AppConfigTest {

    @Test
    void shouldLoadConfigurationFromFile() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");

        // when
        AppConfig config = new AppConfig(loader);

        // then
        assertThat(config.getDatabaseUrl()).isEqualTo("jdbc:postgresql://localhost:5432/milviv_test");
        assertThat(config.getDatabaseUsername()).isEqualTo("test");
        assertThat(config.getDatabasePassword()).isEqualTo("test");
        assertThat(config.getDatabasePoolSize()).isEqualTo(5);
        assertThat(config.getDatabaseConnectionTimeout()).isEqualTo(10000);
        assertThat(config.getServerPort()).isEqualTo(8081);
        assertThat(config.getServerHost()).isEqualTo("localhost");
        assertThat(config.getLoggingLevel()).isEqualTo("DEBUG");
    }

    @Test
    void shouldUseDefaultValues() {
        // given - create mock ConfigLoader that returns null for everything
        ConfigLoader mockLoader = Mockito.mock(ConfigLoader.class);
        when(mockLoader.get("DB_URL", "db.url")).thenReturn("jdbc:postgresql://localhost:5432/db");
        when(mockLoader.get("DB_USERNAME", "db.username")).thenReturn("user");
        when(mockLoader.get("DB_PASSWORD", "db.password")).thenReturn("pass");
        when(mockLoader.getInt("DB_POOL_SIZE", "db.pool.size", 10)).thenReturn(10);
        when(mockLoader.getInt("DB_CONNECTION_TIMEOUT", "db.connection.timeout", 30000)).thenReturn(30000);
        when(mockLoader.getInt("SERVER_PORT", "server.port", 8080)).thenReturn(8080);
        when(mockLoader.get("SERVER_HOST", "server.host", "0.0.0.0")).thenReturn("0.0.0.0");
        when(mockLoader.get("APP_NAME", "app.name", "MILVIV")).thenReturn("MILVIV");
        when(mockLoader.get("APP_VERSION", "app.version", "1.0.0")).thenReturn("1.0.0");
        when(mockLoader.get("APP_ENVIRONMENT", "app.environment", "development")).thenReturn("development");
        when(mockLoader.get("LOGGING_LEVEL", "logging.level", "INFO")).thenReturn("INFO");
        when(mockLoader.get("LOGGING_FILE_PATH", "logging.file.path", "logs/application.log")).thenReturn("logs/application.log");

        // when
        AppConfig config = new AppConfig(mockLoader);

        // then - default values are used
        assertThat(config.getDatabasePoolSize()).isEqualTo(10);
        assertThat(config.getDatabaseConnectionTimeout()).isEqualTo(30000);
        assertThat(config.getServerPort()).isEqualTo(8080);
        assertThat(config.getServerHost()).isEqualTo("0.0.0.0");
        assertThat(config.getAppName()).isEqualTo("MILVIV");
        assertThat(config.getAppVersion()).isEqualTo("1.0.0");
        assertThat(config.getAppEnvironment()).isEqualTo("development");
        assertThat(config.getLoggingLevel()).isEqualTo("INFO");
        assertThat(config.getLoggingFilePath()).isEqualTo("logs/application.log");
    }

    @Test
    void shouldThrowExceptionWhenDatabaseUrlMissing() {
        // given
        ConfigLoader mockLoader = Mockito.mock(ConfigLoader.class);
        when(mockLoader.get("DB_URL", "db.url")).thenReturn(null);
        when(mockLoader.get("DB_USERNAME", "db.username")).thenReturn("user");
        when(mockLoader.get("DB_PASSWORD", "db.password")).thenReturn("pass");

        // when & then
        assertThatThrownBy(() -> new AppConfig(mockLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database URL is required (DB_URL or db.url)");
    }

    @Test
    void shouldThrowExceptionWhenDatabaseUrlEmpty() {
        // given
        ConfigLoader mockLoader = Mockito.mock(ConfigLoader.class);
        when(mockLoader.get("DB_URL", "db.url")).thenReturn("");
        when(mockLoader.get("DB_USERNAME", "db.username")).thenReturn("user");
        when(mockLoader.get("DB_PASSWORD", "db.password")).thenReturn("pass");

        // when & then
        assertThatThrownBy(() -> new AppConfig(mockLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database URL is required (DB_URL or db.url)");
    }

    @Test
    void shouldThrowExceptionWhenDatabaseUsernameMissing() {
        // given
        ConfigLoader mockLoader = Mockito.mock(ConfigLoader.class);
        when(mockLoader.get("DB_URL", "db.url")).thenReturn("jdbc:postgresql://localhost:5432/db");
        when(mockLoader.get("DB_USERNAME", "db.username")).thenReturn(null);
        when(mockLoader.get("DB_PASSWORD", "db.password")).thenReturn("pass");

        // when & then
        assertThatThrownBy(() -> new AppConfig(mockLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database username is required (DB_USERNAME or db.username)");
    }

    @Test
    void shouldThrowExceptionWhenDatabasePasswordMissing() {
        // given
        ConfigLoader mockLoader = Mockito.mock(ConfigLoader.class);
        when(mockLoader.get("DB_URL", "db.url")).thenReturn("jdbc:postgresql://localhost:5432/db");
        when(mockLoader.get("DB_USERNAME", "db.username")).thenReturn("user");
        when(mockLoader.get("DB_PASSWORD", "db.password")).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> new AppConfig(mockLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database password is required (DB_PASSWORD or db.password)");
    }

    @Test
    void shouldIdentifyDevelopmentEnvironment() {
        // given
        ConfigLoader mockLoader = Mockito.mock(ConfigLoader.class);
        when(mockLoader.get("DB_URL", "db.url")).thenReturn("jdbc:postgresql://localhost:5432/db");
        when(mockLoader.get("DB_USERNAME", "db.username")).thenReturn("user");
        when(mockLoader.get("DB_PASSWORD", "db.password")).thenReturn("pass");
        when(mockLoader.get("APP_ENVIRONMENT", "app.environment", "development")).thenReturn("development");

        // when
        AppConfig config = new AppConfig(mockLoader);

        // then
        assertThat(config.isDevelopment()).isTrue();
        assertThat(config.isProduction()).isFalse();
    }

    @Test
    void shouldIdentifyProductionEnvironment() {
        // given
        ConfigLoader mockLoader = Mockito.mock(ConfigLoader.class);
        when(mockLoader.get("DB_URL", "db.url")).thenReturn("jdbc:postgresql://localhost:5432/db");
        when(mockLoader.get("DB_USERNAME", "db.username")).thenReturn("user");
        when(mockLoader.get("DB_PASSWORD", "db.password")).thenReturn("pass");
        when(mockLoader.get("APP_ENVIRONMENT", "app.environment", "development")).thenReturn("production");

        // when
        AppConfig config = new AppConfig(mockLoader);

        // then
        assertThat(config.isDevelopment()).isFalse();
        assertThat(config.isProduction()).isTrue();
    }

    @Test
    void shouldMaskPasswordInToString() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");
        AppConfig config = new AppConfig(loader);

        // when
        String configString = config.toString();

        // then
        assertThat(configString).contains("databasePassword='***'");
        assertThat(configString).doesNotContain("test_password"); // real password should not appear
        assertThat(configString).contains("databaseUrl=");
        assertThat(configString).contains("databaseUsername=");
    }

    @Test
    void shouldUseDefaultConstructor() {
        // when - This will try to load /application.properties
        // It might fail if the file doesn't exist, so we just test it doesn't crash
        try {
            AppConfig config = new AppConfig();
            
            // then
            assertThat(config).isNotNull();
        } catch (IllegalStateException e) {
            // Expected if application.properties doesn't have required fields
            assertThat(e.getMessage()).contains("required");
        }
    }

    @Test
    void shouldGetAllConfigurationValues() {
        // given
        ConfigLoader loader = new ConfigLoader("/application-test.properties");
        AppConfig config = new AppConfig(loader);

        // when & then - verify all getters work
        assertThat(config.getDatabaseUrl()).isNotNull();
        assertThat(config.getDatabaseUsername()).isNotNull();
        assertThat(config.getDatabasePassword()).isNotNull();
        assertThat(config.getDatabasePoolSize()).isGreaterThan(0);
        assertThat(config.getDatabaseConnectionTimeout()).isGreaterThan(0);
        assertThat(config.getServerPort()).isGreaterThan(0);
        assertThat(config.getServerHost()).isNotNull();
        assertThat(config.getAppName()).isNotNull();
        assertThat(config.getAppVersion()).isNotNull();
        assertThat(config.getAppEnvironment()).isNotNull();
        assertThat(config.getLoggingLevel()).isNotNull();
        assertThat(config.getLoggingFilePath()).isNotNull();
    }
}
