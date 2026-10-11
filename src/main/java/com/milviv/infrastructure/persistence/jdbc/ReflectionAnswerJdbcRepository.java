package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.reflection.ReflectionAnswer;
import com.milviv.domain.reflection.ReflectionAnswerRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

/** taskId ответа берётся из факта выполнения: у reflection_answers своей колонки нет. */
public class ReflectionAnswerJdbcRepository implements ReflectionAnswerRepository {

    private static final String SELECT = """
        SELECT a.id, a.user_id, a.completion_id, c.task_id, a.question_id, a.question_text, a.answer, a.created_at
        FROM reflection_answers a
        JOIN task_completions c ON c.id = a.completion_id
        """;

    private final ConnectionProvider connectionProvider;

    public ReflectionAnswerJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(ReflectionAnswer answer) {
        String sql = """
            INSERT INTO reflection_answers (user_id, completion_id, question_id, question_text, answer, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, answer.userId());
            ps.setLong(2, answer.completionId());
            ps.setObject(3, answer.questionId());
            ps.setString(4, answer.questionText());
            ps.setString(5, answer.answer());
            ps.setTimestamp(6, Timestamp.from(answer.createdAt()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert reflection answer", ex);
        }
    }

    @Override
    public List<ReflectionAnswer> findByCompletion(UUID userId, long completionId) {
        String sql = SELECT + " WHERE a.user_id = ? AND a.completion_id = ? ORDER BY a.id";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setLong(2, completionId);
            return list(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list reflection answers", ex);
        }
    }

    @Override
    public List<ReflectionAnswer> findByUser(UUID userId, int limit, int offset) {
        String sql = SELECT + " WHERE a.user_id = ? ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return list(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list reflection answers", ex);
        }
    }

    private static List<ReflectionAnswer> list(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<ReflectionAnswer> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new ReflectionAnswer(
                        rs.getLong("id"),
                        rs.getObject("user_id", UUID.class),
                        rs.getLong("completion_id"),
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("question_id", UUID.class),
                        rs.getString("question_text"),
                        rs.getString("answer"),
                        rs.getTimestamp("created_at").toInstant()));
            }
            return result;
        }
    }
}
