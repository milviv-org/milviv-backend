package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.domain.task.Difficulty;
import com.milviv.domain.task.Task;
import com.milviv.domain.task.TaskFilter;
import com.milviv.domain.task.TaskRepository;
import com.milviv.domain.task.TaskStatus;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class TaskJdbcRepository implements TaskRepository {

    private static final String COLUMNS = "id, user_id, project_id, goal_id, title, description, difficulty, points, "
            + "status, start_date, deadline, reminder_at, completed_at, created_at, updated_at, version";

    private final ConnectionProvider connectionProvider;

    public TaskJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(Task task) {
        String sql = "INSERT INTO tasks (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, task.getId());
            ps.setObject(2, task.getUserId());
            ps.setObject(3, task.getProjectId());
            ps.setObject(4, task.getGoalId());
            ps.setString(5, task.getTitle());
            ps.setString(6, task.getDescription());
            ps.setString(7, task.getDifficulty().name());
            ps.setInt(8, task.getPoints());
            ps.setString(9, task.getStatus().name());
            ps.setObject(10, task.getStartDate());
            ps.setObject(11, task.getDeadline());
            ps.setTimestamp(12, timestamp(task.getReminderAt()));
            ps.setTimestamp(13, timestamp(task.getCompletedAt()));
            ps.setTimestamp(14, timestamp(task.getCreatedAt()));
            ps.setTimestamp(15, timestamp(task.getUpdatedAt()));
            ps.setLong(16, task.getVersion());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert task", ex);
        }
    }

    @Override
    public Optional<Task> findById(UUID userId, UUID taskId) {
        String sql = "SELECT " + COLUMNS + " FROM tasks WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, taskId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find task", ex);
        }
    }

    @Override
    public List<Task> findByUser(UUID userId, TaskFilter filter, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM tasks WHERE user_id = ?"
                + (filter.status() != null ? " AND status = ?" : "")
                + (filter.projectId() != null ? " AND project_id = ?" : "")
                + (filter.goalId() != null ? " AND goal_id = ?" : "")
                + (filter.unassigned() ? " AND project_id IS NULL AND goal_id IS NULL" : "")
                + (filter.hasPeriod() ? " AND (deadline BETWEEN ? AND ? OR (completed_at >= ? AND completed_at < ?))" : "")
                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, userId);
            if (filter.status() != null) {
                ps.setString(i++, filter.status().name());
            }
            if (filter.projectId() != null) {
                ps.setObject(i++, filter.projectId());
            }
            if (filter.goalId() != null) {
                ps.setObject(i++, filter.goalId());
            }
            if (filter.hasPeriod()) {
                ps.setObject(i++, filter.from());
                ps.setObject(i++, filter.to());
                ps.setTimestamp(i++, Timestamp.from(filter.from().atStartOfDay().toInstant(ZoneOffset.UTC)));
                ps.setTimestamp(i++, Timestamp.from(filter.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<Task> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list tasks", ex);
        }
    }

    @Override
    public List<Difficulty> findNotCancelledDifficultiesInProject(UUID userId, UUID projectId) {
        String sql = "SELECT difficulty FROM tasks WHERE user_id = ? AND project_id = ? AND status <> ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, projectId);
            ps.setString(3, TaskStatus.CANCELLED.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<Difficulty> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(Difficulty.valueOf(rs.getString(1)));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to load project task difficulties", ex);
        }
    }

    @Override
    public Optional<LocalDate> findLatestDeadlineInProject(UUID userId, UUID projectId) {
        String sql = "SELECT MAX(deadline) FROM tasks WHERE user_id = ? AND project_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, LocalDate.class));
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find latest task deadline", ex);
        }
    }

    @Override
    public List<Task> findActiveWithDeadlineBetween(UUID userId, LocalDate from, LocalDate to) {
        String sql = "SELECT " + COLUMNS + " FROM tasks WHERE user_id = ? AND status = ? AND deadline BETWEEN ? AND ?"
                + " ORDER BY deadline, id";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, TaskStatus.ACTIVE.name());
            ps.setObject(3, from);
            ps.setObject(4, to);
            return list(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list tasks by deadline", ex);
        }
    }

    @Override
    public List<Task> findCompletedBetween(UUID userId, Instant from, Instant toExclusive) {
        String sql = "SELECT " + COLUMNS + " FROM tasks WHERE user_id = ? AND status = ?"
                + " AND completed_at >= ? AND completed_at < ? ORDER BY completed_at, id";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, TaskStatus.COMPLETED.name());
            ps.setTimestamp(3, timestamp(from));
            ps.setTimestamp(4, timestamp(toExclusive));
            return list(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list completed tasks", ex);
        }
    }

    private List<Task> list(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Task> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        }
    }

    @Override
    public void update(Task task) {
        String sql = """
            UPDATE tasks
            SET project_id = ?, goal_id = ?, title = ?, description = ?, difficulty = ?, points = ?, status = ?,
                start_date = ?, deadline = ?, reminder_at = ?, completed_at = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ? AND version = ?
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, task.getProjectId());
            ps.setObject(2, task.getGoalId());
            ps.setString(3, task.getTitle());
            ps.setString(4, task.getDescription());
            ps.setString(5, task.getDifficulty().name());
            ps.setInt(6, task.getPoints());
            ps.setString(7, task.getStatus().name());
            ps.setObject(8, task.getStartDate());
            ps.setObject(9, task.getDeadline());
            ps.setTimestamp(10, timestamp(task.getReminderAt()));
            ps.setTimestamp(11, timestamp(task.getCompletedAt()));
            ps.setTimestamp(12, timestamp(task.getUpdatedAt()));
            ps.setObject(13, task.getId());
            ps.setObject(14, task.getUserId());
            ps.setLong(15, task.getVersion());

            if (ps.executeUpdate() == 0) {
                throw new OptimisticLockException("Task", task.getId(), task.getVersion());
            }
            task.incrementVersion();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update task", ex);
        }
    }

    @Override
    public boolean delete(UUID userId, UUID taskId) {
        String sql = "DELETE FROM tasks WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, taskId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to delete task", ex);
        }
    }

    private Task mapRow(ResultSet rs) throws SQLException {
        return new Task(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("goal_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                Difficulty.valueOf(rs.getString("difficulty")),
                rs.getInt("points"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("deadline", LocalDate.class),
                instant(rs.getTimestamp("reminder_at")),
                instant(rs.getTimestamp("completed_at")),
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
