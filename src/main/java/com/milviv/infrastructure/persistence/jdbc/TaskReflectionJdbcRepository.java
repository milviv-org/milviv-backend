package com.milviv.infrastructure.persistence.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.reflection.ReflectionQuestion;
import com.milviv.domain.reflection.TaskReflectionRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class TaskReflectionJdbcRepository implements TaskReflectionRepository {

    private final ConnectionProvider connectionProvider;

    public TaskReflectionJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public List<ReflectionQuestion> findQuestions(UUID taskId) {
        String sql = """
            SELECT q.id, q.user_id, q.text, q.created_at
            FROM task_reflection_questions tq
            JOIN reflection_questions q ON q.id = tq.question_id
            WHERE tq.task_id = ?
            ORDER BY CASE WHEN q.user_id IS NULL THEN 0 ELSE 1 END, q.created_at, q.id
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, taskId);
            return ReflectionQuestionJdbcRepository.ReflectionRows.questions(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list task reflection questions", ex);
        }
    }

    @Override
    public void replace(UUID taskId, Collection<UUID> questionIds) {
        Connection connection = connectionProvider.get();
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM task_reflection_questions WHERE task_id = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO task_reflection_questions (task_id, question_id) VALUES (?, ?)")) {
            delete.setObject(1, taskId);
            delete.executeUpdate();
            for (UUID questionId : questionIds) {
                insert.setObject(1, taskId);
                insert.setObject(2, questionId);
                insert.addBatch();
            }
            if (!questionIds.isEmpty()) {
                insert.executeBatch();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to replace task reflection questions", ex);
        }
    }

    @Override
    public void assignStandard(UUID taskId) {
        String sql = "INSERT INTO task_reflection_questions (task_id, question_id) "
                + "SELECT ?, id FROM reflection_questions WHERE user_id IS NULL";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, taskId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to assign standard reflection questions", ex);
        }
    }
}
