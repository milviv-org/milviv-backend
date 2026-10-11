package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.planning.TaskReschedule;
import com.milviv.domain.planning.TaskRescheduleRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class TaskRescheduleJdbcRepository implements TaskRescheduleRepository {

    private final ConnectionProvider connectionProvider;

    public TaskRescheduleJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(TaskReschedule reschedule) {
        String sql = "INSERT INTO task_reschedules (user_id, task_id, from_date, to_date, rescheduled_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, reschedule.userId());
            ps.setObject(2, reschedule.taskId());
            ps.setObject(3, reschedule.fromDate());
            ps.setObject(4, reschedule.toDate());
            ps.setTimestamp(5, Timestamp.from(reschedule.rescheduledAt()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert task reschedule", ex);
        }
    }

    @Override
    public List<TaskReschedule> findBetween(UUID userId, Instant from, Instant toExclusive) {
        String sql = "SELECT user_id, task_id, from_date, to_date, rescheduled_at FROM task_reschedules"
                + " WHERE user_id = ? AND rescheduled_at >= ? AND rescheduled_at < ? ORDER BY rescheduled_at, id";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setTimestamp(2, Timestamp.from(from));
            ps.setTimestamp(3, Timestamp.from(toExclusive));
            try (ResultSet rs = ps.executeQuery()) {
                List<TaskReschedule> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new TaskReschedule(
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("task_id", UUID.class),
                            rs.getObject("from_date", LocalDate.class),
                            rs.getObject("to_date", LocalDate.class),
                            rs.getTimestamp("rescheduled_at").toInstant()));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list task reschedules", ex);
        }
    }
}
