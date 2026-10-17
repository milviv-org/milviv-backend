package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.ai.AiMemory;
import com.milviv.domain.ai.AiMemoryRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class AiMemoryJdbcRepository implements AiMemoryRepository {

    private final ConnectionProvider connectionProvider;

    public AiMemoryJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void lockUser(UUID userId) {
        try (PreparedStatement ps = connectionProvider.get().prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, userId);
            ps.executeQuery().close();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to lock user for AI memory", ex);
        }
    }

    @Override
    public void add(AiMemory memory) {
        String sql = "INSERT INTO ai_memories (id, user_id, content, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, memory.id());
            ps.setObject(2, memory.userId());
            ps.setString(3, memory.content());
            ps.setTimestamp(4, Timestamp.from(memory.createdAt()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert AI memory", ex);
        }
    }

    @Override
    public List<AiMemory> findByUser(UUID userId, int limit) {
        String sql = "SELECT id, user_id, content, created_at FROM ai_memories WHERE user_id = ? "
                + "ORDER BY created_at DESC, id LIMIT ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<AiMemory> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new AiMemory(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                            rs.getString("content"), rs.getTimestamp("created_at").toInstant()));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list AI memories", ex);
        }
    }

    @Override
    public int count(UUID userId) {
        try (PreparedStatement ps = connectionProvider.get().prepareStatement("SELECT COUNT(*) FROM ai_memories WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to count AI memories", ex);
        }
    }

    @Override
    public boolean delete(UUID userId, UUID memoryId) {
        try (PreparedStatement ps = connectionProvider.get().prepareStatement("DELETE FROM ai_memories WHERE id = ? AND user_id = ?")) {
            ps.setObject(1, memoryId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to delete AI memory", ex);
        }
    }
}
