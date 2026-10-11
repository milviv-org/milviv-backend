package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.gamification.PointsEntry;
import com.milviv.domain.gamification.PointsHistoryRepository;
import com.milviv.domain.gamification.PointsKind;
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
            ps.setString(3, truncate(reason));
            ps.setObject(4, taskId);
            ps.setTimestamp(5, Timestamp.from(at));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to record points", ex);
        }
    }

    @Override
    public void recordRewardRedeemed(UUID userId, UUID rewardId, int cost, String reason, Instant at) {
        String sql = """
            INSERT INTO points_history (user_id, kind, amount, reason, reward_id, created_at)
            VALUES (?, 'REWARD_REDEEMED', ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, -cost);
            ps.setString(3, truncate(reason));
            ps.setObject(4, rewardId);
            ps.setTimestamp(5, Timestamp.from(at));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to record reward redemption", ex);
        }
    }

    /** Блокировка строки пользователя: отдельного счёта баланса нет (AD-16), блокировать больше нечего. */
    @Override
    public void lockBalance(UUID userId) {
        String sql = "SELECT id FROM users WHERE id = ? FOR UPDATE";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("User not found for balance lock: " + userId);
                }
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to lock balance", ex);
        }
    }

    @Override
    public long balance(UUID userId) {
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM points_history WHERE user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to calculate balance", ex);
        }
    }

    @Override
    public List<PointsEntry> findByUser(UUID userId, int limit, int offset) {
        String sql = """
            SELECT ph.id, ph.kind, ph.amount, ph.reason, ph.task_id, ph.reward_id, ph.created_at,
                   COALESCE(r.hidden, FALSE) AS reward_hidden
            FROM points_history ph
            LEFT JOIN rewards r ON r.id = ph.reward_id
            WHERE ph.user_id = ?
            ORDER BY ph.created_at DESC, ph.id DESC
            LIMIT ? OFFSET ?
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<PointsEntry> result = new ArrayList<>();
                while (rs.next()) {
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    result.add(new PointsEntry(
                            rs.getLong("id"),
                            PointsKind.valueOf(rs.getString("kind")),
                            rs.getInt("amount"),
                            rs.getString("reason"),
                            rs.getObject("task_id", UUID.class),
                            rs.getObject("reward_id", UUID.class),
                            rs.getBoolean("reward_hidden"),
                            createdAt == null ? null : createdAt.toInstant()));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list points history", ex);
        }
    }

    private static String truncate(String reason) {
        return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
    }
}
