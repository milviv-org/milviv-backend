package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.gamification.HiddenRewardsPasswordRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

/** Хеш хранится в users.hidden_rewards_password_hash (V1). */
public class HiddenRewardsPasswordJdbcRepository implements HiddenRewardsPasswordRepository {

    private final ConnectionProvider connectionProvider;

    public HiddenRewardsPasswordJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public Optional<String> findHash(UUID userId) {
        String sql = "SELECT hidden_rewards_password_hash FROM users WHERE id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to read hidden rewards password", ex);
        }
    }

    /** Версию пользователя не меняет: пароль не входит в редактируемые поля профиля. */
    @Override
    public void setHash(UUID userId, String passwordHash) {
        String sql = "UPDATE users SET hidden_rewards_password_hash = ? WHERE id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setObject(2, userId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to set hidden rewards password", ex);
        }
    }
}
