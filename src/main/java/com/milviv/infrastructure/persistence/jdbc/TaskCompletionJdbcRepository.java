package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

import com.milviv.domain.task.TaskCompletion;
import com.milviv.domain.task.TaskCompletionRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class TaskCompletionJdbcRepository implements TaskCompletionRepository {

    private final ConnectionProvider connectionProvider;

    public TaskCompletionJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(TaskCompletion completion) {
        String sql = """
            INSERT INTO task_completions (user_id, task_id, difficulty, points, planned_date, deadline, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, completion.userId());
            ps.setObject(2, completion.taskId());
            ps.setString(3, completion.difficulty().name());
            ps.setInt(4, completion.points());
            ps.setObject(5, completion.plannedDate());
            ps.setObject(6, completion.deadline());
            ps.setTimestamp(7, Timestamp.from(completion.completedAt()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert task completion", ex);
        }
    }
}
