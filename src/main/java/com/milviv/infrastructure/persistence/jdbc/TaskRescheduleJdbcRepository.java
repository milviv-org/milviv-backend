package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

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
}
