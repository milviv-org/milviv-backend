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
import com.milviv.domain.gamification.Reward;
import com.milviv.domain.gamification.RewardRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class RewardJdbcRepository implements RewardRepository {

    private static final String COLUMNS = "id, user_id, title, description, cost, hidden, created_at, updated_at, version";

    private final ConnectionProvider connectionProvider;

    public RewardJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(Reward reward) {
        String sql = "INSERT INTO rewards (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, reward.getId());
            ps.setObject(2, reward.getUserId());
            ps.setString(3, reward.getTitle());
            ps.setString(4, reward.getDescription());
            ps.setInt(5, reward.getCost());
            ps.setBoolean(6, reward.isHidden());
            ps.setTimestamp(7, timestamp(reward.getCreatedAt()));
            ps.setTimestamp(8, timestamp(reward.getUpdatedAt()));
            ps.setLong(9, reward.getVersion());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert reward", ex);
        }
    }

    @Override
    public Optional<Reward> findById(UUID userId, UUID rewardId) {
        String sql = "SELECT " + COLUMNS + " FROM rewards WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, rewardId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find reward", ex);
        }
    }

    @Override
    public List<Reward> findByUser(UUID userId, boolean includeHidden, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM rewards WHERE user_id = ?"
                + (includeHidden ? "" : " AND hidden = FALSE")
                + " ORDER BY cost, created_at, id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<Reward> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list rewards", ex);
        }
    }

    @Override
    public void update(Reward reward) {
        String sql = """
            UPDATE rewards SET title = ?, description = ?, cost = ?, hidden = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ? AND version = ?
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, reward.getTitle());
            ps.setString(2, reward.getDescription());
            ps.setInt(3, reward.getCost());
            ps.setBoolean(4, reward.isHidden());
            ps.setTimestamp(5, timestamp(reward.getUpdatedAt()));
            ps.setObject(6, reward.getId());
            ps.setObject(7, reward.getUserId());
            ps.setLong(8, reward.getVersion());

            if (ps.executeUpdate() == 0) {
                throw new OptimisticLockException("Reward", reward.getId(), reward.getVersion());
            }
            reward.incrementVersion();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update reward", ex);
        }
    }

    @Override
    public boolean delete(UUID userId, UUID rewardId) {
        String sql = "DELETE FROM rewards WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, rewardId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to delete reward", ex);
        }
    }

    private Reward mapRow(ResultSet rs) throws SQLException {
        return new Reward(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getInt("cost"),
                rs.getBoolean("hidden"),
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
