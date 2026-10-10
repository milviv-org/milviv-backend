package com.milviv.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.infrastructure.container.Inject;
import io.github.cdimascio.dotenv.Dotenv;

/**
 * Загрузчик конфигурации с поддержкой приоритетов.
 * 
 * Приоритет (от высшего к низшему):
 * 1. Системные переменные окружения (System.getenv())
 * 2. .env файл (для development)
 * 3. application.properties (defaults)
 */
public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private final Properties properties;
    private final Dotenv dotenv;

    @Inject
    public ConfigLoader() {
        this("/application.properties", true);
    }

    public ConfigLoader(String propertiesFile) {
        this(propertiesFile, true);
    }
    
    /**
     * Конструктор с контролем загрузки .env файла.
     * 
     * @param propertiesFile Путь к properties файлу
     * @param loadDotenv Загружать ли .env файл (false для тестов)
     */
    public ConfigLoader(String propertiesFile, boolean loadDotenv) {
        // Загружаем .env файл (только если не тестовое окружение)
        // В тестах отключаем, чтобы локальный .env не влиял на тесты
        if (loadDotenv && !propertiesFile.contains("test")) {
            this.dotenv = Dotenv.configure()
                    .ignoreIfMissing()  // не падать если .env нет
                    .ignoreIfMalformed() // не падать на ошибки парсинга
                    .load();
            
            if (dotenv.entries().isEmpty()) {
                logger.debug(".env file not found or empty");
            } else {
                logger.info(".env file loaded with {} entries", dotenv.entries().size());
            }
        } else {
            this.dotenv = null;
            if (propertiesFile.contains("test")) {
                logger.debug(".env loading disabled (test properties file detected)");
            } else {
                logger.debug(".env loading disabled (test mode)");
            }
        }
        
        // Загружаем application.properties
        properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream(propertiesFile)) {
            if (input == null) {
                logger.warn("Properties file not found: {}", propertiesFile);
            } else {
                properties.load(input);
                logger.info("Loaded configuration from: {}", propertiesFile);
            }
        } catch (IOException e) {
            logger.error("Failed to load properties file: {}", propertiesFile, e);
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    /**
     * Получить значение конфигурации с учетом приоритетов.
     * 
     * @param envKey Имя переменной окружения (например, "DB_URL")
     * @param propertyKey Ключ в properties файле (например, "db.url")
     * @return Значение конфигурации
     */
    public String get(String envKey, String propertyKey) {
        // Приоритет 1: Системные переменные окружения (production)
        String sysEnv = System.getenv(envKey);
        if (sysEnv != null && !sysEnv.isEmpty()) {
            logger.debug("Using system env: {} = {}", envKey, maskSensitive(envKey, sysEnv));
            return sysEnv;
        }

        // Приоритет 2: .env файл (development)
        if (dotenv != null) {
            String dotenvValue = dotenv.get(envKey);
            if (dotenvValue != null && !dotenvValue.isEmpty()) {
                logger.debug("Using .env: {} = {}", envKey, maskSensitive(envKey, dotenvValue));
                return dotenvValue;
            }
        }

        // Приоритет 3: application.properties (defaults)
        String propValue = properties.getProperty(propertyKey);
        if (propValue != null) {
            logger.debug("Using property: {} = {}", propertyKey, maskSensitive(propertyKey, propValue));
            return propValue;
        }

        // Ничего не найдено
        logger.warn("Configuration not found: env={}, property={}", envKey, propertyKey);
        return null;
    }

    /**
     * Get configuration value with default fallback.
     */
    public String get(String envKey, String propertyKey, String defaultValue) {
        String value = get(envKey, propertyKey);
        return value != null ? value : defaultValue;
    }

    /**
     * Get integer configuration value.
     */
    public int getInt(String envKey, String propertyKey, int defaultValue) {
        String value = get(envKey, propertyKey);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.error("Invalid integer value for {}/{}: {}", envKey, propertyKey, value);
            return defaultValue;
        }
    }

    /**
     * Get boolean configuration value.
     */
    public boolean getBoolean(String envKey, String propertyKey, boolean defaultValue) {
        String value = get(envKey, propertyKey);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Mask sensitive values in logs (passwords, tokens, etc.)
     */
    private String maskSensitive(String key, String value) {
        if (key.toLowerCase().contains("password") || 
            key.toLowerCase().contains("secret") || 
            key.toLowerCase().contains("token")) {
            return "***";
        }
        return value;
    }

    /**
     * Get property directly from properties file (ignoring env vars)
     */
    public String getProperty(String key) {
        return properties.getProperty(key);
    }
}
