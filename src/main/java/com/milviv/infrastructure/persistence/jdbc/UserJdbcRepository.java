package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.user.User;
import com.milviv.domain.user.UserRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class UserJdbcRepository implements UserRepository {

    private static final String COLUMNS = "id, email, name, password_hash, created_at, updated_at, version";

    private final ConnectionProvider connectionProvider;

    public UserJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(User user) {
        String sql = "INSERT INTO users (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, user.getId());
            ps.setString(2, user.getEmail());
            ps.setString(3, user.getName());
            ps.setString(4, user.getPasswordHash());
            ps.setTimestamp(5, Timestamp.from(user.getCreatedAt()));
            ps.setTimestamp(6, Timestamp.from(user.getUpdatedAt()));
            ps.setLong(7, user.getVersion());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert user", ex);
        }
    }

    @Override
    public Optional<User> findById(UUID id) {
        return findOne("SELECT " + COLUMNS + " FROM users WHERE id = ?", id);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return findOne("SELECT " + COLUMNS + " FROM users WHERE email = ?", email);
    }

    @Override
    public boolean existsByEmail(String email) {
        String sql = "SELECT 1 FROM users WHERE email = ? LIMIT 1";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to check email existence", ex);
        }
    }

    private Optional<User> findOne(String sql, Object param) {
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find user", ex);
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("password_hash"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version")
        );
    }
}
