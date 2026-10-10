package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.domain.user.User;
import com.milviv.domain.user.UserRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class UserJdbcRepository implements UserRepository {

    private final ConnectionProvider connectionProvider;

    public UserJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void save(User user) {
        if (existsById(user.getId())) {
            update(user);
        } else {
            insert(user);
        }
    }

    @Override
    public Optional<User> findById(UUID id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(mapRow(rs));
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find user by id: " + id, ex);
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(mapRow(rs));
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find user by username: " + username, ex);
        }
    }

    @Override
    public boolean existsByUsername(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ? LIMIT 1";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeQuery().next();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to check username existence", ex);
        }
    }

    @Override
    public boolean existsByEmail(String email) {
        String sql = "SELECT 1 FROM users WHERE email = ? LIMIT 1";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, email);
            return ps.executeQuery().next();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to check email existence", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void insert(User user) {
        String sql = """
            INSERT INTO users (id, username, email, password_hash, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, user.getId());
            ps.setString(2, user.getUsername());
            ps.setString(3, user.getEmail());
            ps.setString(4, user.getPasswordHash());
            ps.setTimestamp(5, Timestamp.from(user.getCreatedAt()));
            ps.setTimestamp(6, Timestamp.from(user.getUpdatedAt()));
            ps.setLong(7, user.getVersion());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert user", ex);
        }
    }

    private void update(User user) {
        String sql = """
            UPDATE users
            SET username = ?, email = ?, password_hash = ?, updated_at = NOW(), version = version + 1
            WHERE id = ? AND version = ?
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getEmail());
            ps.setString(3, user.getPasswordHash());
            ps.setObject(4, user.getId());
            ps.setLong(5, user.getVersion());

            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new OptimisticLockException("User", user.getId(), user.getVersion());
            }
            user.incrementVersion();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update user", ex);
        }
    }

    private boolean existsById(UUID id) {
        String sql = "SELECT 1 FROM users WHERE id = ? LIMIT 1";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeQuery().next();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to check user existence by id", ex);
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        return new User(
            (UUID) rs.getObject("id"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("version")
        );
    }
}
