package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.milviv.domain.gamification.PointsHistoryRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class PointsHistoryJdbcRepository implements PointsHistoryRepository {

    /** points_history.reason — VARCHAR(255) */
    private static final int MAX_REASON_LENGTH = 255;

    private final ConnectionProvider connectionProvider;

    public PointsHistoryJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void recordTaskCompleted(UUID userId, UUID taskId, int points, String reason, Instant at) {
        String sql = """
            INSERT INTO points_history (user_id, kind, amount, reason, task_id, created_at)
            VALUES (?, 'TASK_COMPLETED', ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, points);
            ps.setString(3, reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason);
            ps.setObject(4, taskId);
            ps.setTimestamp(5, Timestamp.from(at));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to record points", ex);
        }
    }
}
