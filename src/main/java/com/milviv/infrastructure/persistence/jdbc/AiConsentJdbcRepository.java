package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.ai.AiConsentRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class AiConsentJdbcRepository implements AiConsentRepository {

    private final ConnectionProvider connectionProvider;

    public AiConsentJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public Optional<Instant> findAcceptedAt(UUID userId) {
        String sql = "SELECT accepted_at FROM ai_consents WHERE user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to read AI consent", ex);
        }
    }

    /**
     * Сначала блокируется строка пользователя: одновременные согласия одного пользователя
     * идут по очереди, второе видит первое и ничего не вставляет. Ловить нарушение ключа нельзя:
     * в PostgreSQL ошибка прерывает всю транзакцию.
     */
    @Override
    public boolean accept(UUID userId, Instant at) {
        try (PreparedStatement lock = connectionProvider.get().prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE");
             PreparedStatement insert = connectionProvider.get().prepareStatement(
                     "INSERT INTO ai_consents (user_id, accepted_at) SELECT ?, ? "
                             + "WHERE NOT EXISTS (SELECT 1 FROM ai_consents WHERE user_id = ?)")) {
            lock.setObject(1, userId);
            lock.executeQuery().close();
            insert.setObject(1, userId);
            insert.setTimestamp(2, Timestamp.from(at));
            insert.setObject(3, userId);
            return insert.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to save AI consent", ex);
        }
    }
}
