package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.auth.RefreshToken;
import com.milviv.domain.user.User;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.RefreshTokenJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.UserJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;

/**
 * SQL репозиториев на H2. Таблицы повторяют V1 + V15 (только нужные колонки):
 * полные миграции используют возможности PostgreSQL, которых нет в H2.
 */
class AuthJdbcRepositoriesH2Test {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private Connection keepAlive;
    private TransactionManager tx;
    private UserJdbcRepository users;
    private RefreshTokenJdbcRepository refreshTokens;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:auth" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        keepAlive = dataSource.getConnection();
        try (Statement st = keepAlive.createStatement()) {
            st.execute("""
                CREATE TABLE users (
                    id UUID PRIMARY KEY,
                    email VARCHAR(255) NOT NULL UNIQUE,
                    name VARCHAR(100) NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP, updated_at TIMESTAMP,
                    version BIGINT NOT NULL DEFAULT 0)""");
            st.execute("""
                CREATE TABLE refresh_tokens (
                    id UUID PRIMARY KEY,
                    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    family_id UUID NOT NULL,
                    token_hash VARCHAR(64) NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    session_expires_at TIMESTAMP NOT NULL,
                    revoked_at TIMESTAMP)""");
        }

        TransactionalConnectionProvider connections = new TransactionalConnectionProvider();
        tx = new JdbcTransactionManager(dataSource);
        users = new UserJdbcRepository(connections);
        refreshTokens = new RefreshTokenJdbcRepository(connections);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    @Test
    void users_shouldRoundTrip() {
        User user = User.register("A@B.C", "Абай", "hash", NOW);

        tx.execute(() -> users.add(user));

        User loaded = tx.execute(() -> users.findByEmail("a@b.c")).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(user.getId());
        assertThat(loaded.getName()).isEqualTo("Абай");
        assertThat(loaded.getPasswordHash()).isEqualTo("hash");
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(tx.execute(() -> users.findById(user.getId()))).isPresent();
        assertThat(tx.execute(() -> users.existsByEmail("a@b.c"))).isTrue();
        assertThat(tx.execute(() -> users.existsByEmail("x@b.c"))).isFalse();
    }

    @Test
    void refreshTokens_shouldRoundTrip_andRevokeFamily() {
        User user = User.register("a@b.c", "A", "hash", NOW);
        RefreshToken first = RefreshToken.startSession(user.getId(), "h1", NOW,
                NOW.plus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(90)));
        RefreshToken second = first.rotate("h2", NOW.plusSeconds(10), NOW.plus(Duration.ofDays(31)));
        RefreshToken otherSession = RefreshToken.startSession(user.getId(), "h3", NOW,
                NOW.plus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(90)));

        tx.execute(() -> {
            users.add(user);
            refreshTokens.add(first);
            refreshTokens.add(second);
            refreshTokens.add(otherSession);
        });

        RefreshToken loaded = tx.execute(() -> refreshTokens.findByHashForUpdate("h2")).orElseThrow();
        assertThat(loaded.getFamilyId()).isEqualTo(first.getFamilyId());
        assertThat(loaded.getSessionExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
        assertThat(loaded.isRevoked()).isFalse();

        Instant revokedAt = NOW.plus(1, ChronoUnit.HOURS);
        tx.execute(() -> refreshTokens.revokeFamily(first.getFamilyId(), revokedAt));

        assertThat(tx.execute(() -> refreshTokens.findByHashForUpdate("h1")).orElseThrow().getRevokedAt()).isEqualTo(revokedAt);
        assertThat(tx.execute(() -> refreshTokens.findByHashForUpdate("h2")).orElseThrow().isRevoked()).isTrue();
        assertThat(tx.execute(() -> refreshTokens.findByHashForUpdate("h3")).orElseThrow().isRevoked()).isFalse();
        assertThat(tx.execute(() -> refreshTokens.findByHashForUpdate("missing"))).isEmpty();
    }

    @Test
    void revoke_shouldNotOverwriteEarlierRevocation() {
        User user = User.register("a@b.c", "A", "hash", NOW);
        RefreshToken token = RefreshToken.startSession(user.getId(), "h1", NOW,
                NOW.plus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(90)));
        tx.execute(() -> {
            users.add(user);
            refreshTokens.add(token);
        });

        tx.execute(() -> refreshTokens.revoke(token.getId(), NOW.plusSeconds(1)));
        tx.execute(() -> refreshTokens.revoke(token.getId(), NOW.plusSeconds(99)));

        assertThat(tx.execute(() -> refreshTokens.findByHashForUpdate("h1")).orElseThrow().getRevokedAt())
                .isEqualTo(NOW.plusSeconds(1));
    }
}
