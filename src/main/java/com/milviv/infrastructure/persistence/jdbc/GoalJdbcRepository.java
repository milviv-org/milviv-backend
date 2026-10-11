package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.domain.goal.Goal;
import com.milviv.domain.goal.GoalRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class GoalJdbcRepository implements GoalRepository {

    private static final String COLUMNS = "id, user_id, title, description, created_at, updated_at, version";

    private final ConnectionProvider connectionProvider;

    public GoalJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(Goal goal) {
        String sql = "INSERT INTO goals (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, goal.getId());
            ps.setObject(2, goal.getUserId());
            ps.setString(3, goal.getTitle());
            ps.setString(4, goal.getDescription());
            ps.setTimestamp(5, timestamp(goal.getCreatedAt()));
            ps.setTimestamp(6, timestamp(goal.getUpdatedAt()));
            ps.setLong(7, goal.getVersion());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert goal", ex);
        }
    }

    @Override
    public Optional<Goal> findById(UUID userId, UUID goalId) {
        String sql = "SELECT " + COLUMNS + " FROM goals WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, goalId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find goal", ex);
        }
    }

    @Override
    public List<Goal> findByUser(UUID userId, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM goals WHERE user_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<Goal> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list goals", ex);
        }
    }

    @Override
    public void update(Goal goal) {
        String sql = """
            UPDATE goals SET title = ?, description = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ? AND version = ?
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, goal.getTitle());
            ps.setString(2, goal.getDescription());
            ps.setTimestamp(3, timestamp(goal.getUpdatedAt()));
            ps.setObject(4, goal.getId());
            ps.setObject(5, goal.getUserId());
            ps.setLong(6, goal.getVersion());

            if (ps.executeUpdate() == 0) {
                throw new OptimisticLockException("Goal", goal.getId(), goal.getVersion());
            }
            goal.incrementVersion();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update goal", ex);
        }
    }

    @Override
    public boolean delete(UUID userId, UUID goalId) {
        String sql = "DELETE FROM goals WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, goalId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to delete goal", ex);
        }
    }

    private Goal mapRow(ResultSet rs) throws SQLException {
        return new Goal(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getLong("version")
        );
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
