package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.milviv.infrastructure.config.AppConfig;
import com.milviv.infrastructure.persistence.DatabaseConnectionManager;

/**
 * Unit тесты для DatabaseConnectionManager.
 * Используем H2 in-memory database для тестирования.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseConnectionManagerTest {

    private AppConfig mockConfig;
    private DatabaseConnectionManager manager;

    @BeforeEach
    void setUp() {
        mockConfig = mock(AppConfig.class);
        when(mockConfig.getDatabaseUrl()).thenReturn("jdbc:h2:mem:testdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        when(mockConfig.getDatabaseUsername()).thenReturn("sa");
        when(mockConfig.getDatabasePassword()).thenReturn("");
        when(mockConfig.getDatabasePoolSize()).thenReturn(5);
        when(mockConfig.getDatabaseConnectionTimeout()).thenReturn(3000);
        when(mockConfig.isDevelopment()).thenReturn(true);

        // Singleton убран — контейнер управляет временем жизни
        // В тестах создаём напрямую через конструктор
        manager = new DatabaseConnectionManager(mockConfig);
    }

    @Test
    @Order(1)
    @DisplayName("Должен создать instance через конструктор")
    void shouldCreateInstance() {
        assertThat(manager).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Должен получить connection из пула")
    void shouldGetConnectionFromPool() throws SQLException {
        // given — manager создан в setUp()

        // when
        Connection connection = manager.getConnection();

        // then
        assertThat(connection).isNotNull();
        assertThat(connection.isClosed()).isFalse();
        
        // cleanup
        connection.close();
    }

    @Test
    @Order(3)
    @DisplayName("Должен переиспользовать connection из пула")
    void shouldReuseConnectionFromPool() throws SQLException {
        // given — manager создан в setUp()

        // when - берем и возвращаем соединение несколько раз
        Connection conn1 = manager.getConnection();
        conn1.close();
        
        Connection conn2 = manager.getConnection();
        conn2.close();
        
        Connection conn3 = manager.getConnection();

        // then - соединения успешно получены
        assertThat(conn1).isNotNull();
        assertThat(conn2).isNotNull();
        assertThat(conn3).isNotNull();
        
        // cleanup
        conn3.close();
    }

    @Test
    @Order(4)
    @DisplayName("Должен возвращать pool statistics")
    void shouldReturnPoolStats() {
        // given — manager создан в setUp()

        // when
        DatabaseConnectionManager.PoolStats stats = manager.getPoolStats();

        // then
        assertThat(stats).isNotNull();
        assertThat(stats.total()).isGreaterThanOrEqualTo(0);
        assertThat(stats.active()).isGreaterThanOrEqualTo(0);
        assertThat(stats.idle()).isGreaterThanOrEqualTo(0);
        assertThat(stats.waiting()).isEqualTo(0); // никто не ждет
    }

    @Test
    @Order(5)
    @DisplayName("PoolStats toString должен быть читаемым")
    void poolStatsToStringShouldBeReadable() {
        // given
        DatabaseConnectionManager.PoolStats stats = new DatabaseConnectionManager.PoolStats(10, 5, 5, 0);

        // when
        String result = stats.toString();

        // then
        assertThat(result).contains("total=10");
        assertThat(result).contains("active=5");
        assertThat(result).contains("idle=5");
        assertThat(result).contains("waiting=0");
    }

    @Test
    @Order(6)
    @DisplayName("Health check должен возвращать true для работающей БД")
    void shouldReturnTrueForHealthyDatabase() {
        // given — manager создан в setUp()

        // when
        boolean healthy = manager.isHealthy();

        // then
        assertThat(healthy).isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("Должен предоставлять DataSource")
    void shouldProvideDataSource() {
        // given — manager создан в setUp()

        // when
        var dataSource = manager.getDataSource();

        // then
        assertThat(dataSource).isNotNull();
    }

    @Test
    @Order(8)
    @DisplayName("isClosed должен возвращать false для активного пула")
    void isClosedShouldReturnFalseForActivePool() {
        // given — manager создан в setUp()

        // when
        boolean closed = manager.isClosed();

        // then
        assertThat(closed).isFalse();
    }

    @Test
    @Order(9)
    @DisplayName("Должен корректно закрывать пул")
    void shouldShutdownGracefully() {
        // given — manager создан в setUp()
        assertThat(manager.isClosed()).isFalse();

        // when
        manager.shutdown();

        // then
        assertThat(manager.isClosed()).isTrue();
    }

    @AfterEach
    void tearDown() {
        if (!manager.isClosed()) {
            manager.shutdown();
        }
    }
}

