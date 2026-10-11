package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.domain.project.Project;
import com.milviv.domain.project.ProjectRepository;
import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.task.Difficulty;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class ProjectJdbcRepository implements ProjectRepository {

    private static final String COLUMNS = "id, user_id, goal_id, title, description, calculated_difficulty, status, "
            + "deadline, completed_at, created_at, updated_at, version";

    private final ConnectionProvider connectionProvider;

    public ProjectJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(Project project) {
        String sql = "INSERT INTO projects (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, project.getId());
            ps.setObject(2, project.getUserId());
            ps.setObject(3, project.getGoalId());
            ps.setString(4, project.getTitle());
            ps.setString(5, project.getDescription());
            ps.setString(6, name(project.getCalculatedDifficulty()));
            ps.setString(7, project.getStatus().name());
            ps.setObject(8, project.getDeadline());
            ps.setTimestamp(9, timestamp(project.getCompletedAt()));
            ps.setTimestamp(10, timestamp(project.getCreatedAt()));
            ps.setTimestamp(11, timestamp(project.getUpdatedAt()));
            ps.setLong(12, project.getVersion());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert project", ex);
        }
    }

    @Override
    public Optional<Project> findById(UUID userId, UUID projectId) {
        return findOne(userId, projectId, "");
    }

    @Override
    public Optional<Project> findByIdForUpdate(UUID userId, UUID projectId) {
        return findOne(userId, projectId, " FOR UPDATE");
    }

    private Optional<Project> findOne(UUID userId, UUID projectId, String lockClause) {
        String sql = "SELECT " + COLUMNS + " FROM projects WHERE id = ? AND user_id = ?" + lockClause;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, projectId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find project", ex);
        }
    }

    @Override
    public List<Project> findByUser(UUID userId, ProjectStatus status, UUID goalId, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM projects WHERE user_id = ?"
                + (status != null ? " AND status = ?" : "")
                + (goalId != null ? " AND goal_id = ?" : "")
                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, userId);
            if (status != null) {
                ps.setString(i++, status.name());
            }
            if (goalId != null) {
                ps.setObject(i++, goalId);
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<Project> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list projects", ex);
        }
    }

    @Override
    public void update(Project project) {
        String sql = """
            UPDATE projects
            SET goal_id = ?, title = ?, description = ?, status = ?, deadline = ?, completed_at = ?,
                updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ? AND version = ?
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, project.getGoalId());
            ps.setString(2, project.getTitle());
            ps.setString(3, project.getDescription());
            ps.setString(4, project.getStatus().name());
            ps.setObject(5, project.getDeadline());
            ps.setTimestamp(6, timestamp(project.getCompletedAt()));
            ps.setTimestamp(7, timestamp(project.getUpdatedAt()));
            ps.setObject(8, project.getId());
            ps.setObject(9, project.getUserId());
            ps.setLong(10, project.getVersion());

            if (ps.executeUpdate() == 0) {
                throw new OptimisticLockException("Project", project.getId(), project.getVersion());
            }
            project.incrementVersion();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update project", ex);
        }
    }

    @Override
    public void updateCalculatedDifficulty(UUID userId, UUID projectId, Difficulty difficulty) {
        String sql = "UPDATE projects SET calculated_difficulty = ? WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, name(difficulty));
            ps.setObject(2, projectId);
            ps.setObject(3, userId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update project difficulty", ex);
        }
    }

    @Override
    public boolean delete(UUID userId, UUID projectId) {
        String sql = "DELETE FROM projects WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, projectId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to delete project", ex);
        }
    }

    private Project mapRow(ResultSet rs) throws SQLException {
        String difficulty = rs.getString("calculated_difficulty");
        return new Project(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("goal_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                difficulty == null ? null : Difficulty.valueOf(difficulty),
                ProjectStatus.valueOf(rs.getString("status")),
                rs.getObject("deadline", LocalDate.class),
                instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getLong("version")
        );
    }

    private static String name(Difficulty difficulty) {
        return difficulty == null ? null : difficulty.name();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
