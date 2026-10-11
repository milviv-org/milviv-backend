package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.reflection.ReflectionQuestion;
import com.milviv.domain.reflection.ReflectionQuestionRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class ReflectionQuestionJdbcRepository implements ReflectionQuestionRepository {

    private final ConnectionProvider connectionProvider;

    public ReflectionQuestionJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(ReflectionQuestion question) {
        String sql = "INSERT INTO reflection_questions (id, user_id, text, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, question.getId());
            ps.setObject(2, question.getUserId());
            ps.setString(3, question.getText());
            ps.setTimestamp(4, Timestamp.from(question.getCreatedAt()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert reflection question", ex);
        }
    }

    @Override
    public List<ReflectionQuestion> findAvailable(UUID userId) {
        String sql = """
            SELECT id, user_id, text, created_at FROM reflection_questions
            WHERE user_id IS NULL OR user_id = ?
            ORDER BY CASE WHEN user_id IS NULL THEN 0 ELSE 1 END, created_at, id
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            return ReflectionRows.questions(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list reflection questions", ex);
        }
    }

    @Override
    public Optional<ReflectionQuestion> findOwn(UUID userId, UUID questionId) {
        String sql = "SELECT id, user_id, text, created_at FROM reflection_questions WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, questionId);
            ps.setObject(2, userId);
            List<ReflectionQuestion> found = ReflectionRows.questions(ps);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find reflection question", ex);
        }
    }

    @Override
    public int countAvailable(UUID userId, Collection<UUID> questionIds) {
        if (questionIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", Collections.nCopies(questionIds.size(), "?"));
        String sql = "SELECT COUNT(*) FROM reflection_questions WHERE (user_id IS NULL OR user_id = ?) AND id IN ("
                + placeholders + ")";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, userId);
            for (UUID id : questionIds) {
                ps.setObject(i++, id);
            }
            return count(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to count reflection questions", ex);
        }
    }

    @Override
    public int countOwn(UUID userId) {
        String sql = "SELECT COUNT(*) FROM reflection_questions WHERE user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            return count(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to count reflection questions", ex);
        }
    }

    @Override
    public void updateText(ReflectionQuestion question) {
        String sql = "UPDATE reflection_questions SET text = ? WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, question.getText());
            ps.setObject(2, question.getId());
            ps.setObject(3, question.getUserId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update reflection question", ex);
        }
    }

    @Override
    public boolean deleteOwn(UUID userId, UUID questionId) {
        String sql = "DELETE FROM reflection_questions WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, questionId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to delete reflection question", ex);
        }
    }

    private static int count(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Общее чтение вопросов для репозиториев рефлексии. */
    static final class ReflectionRows {

        private ReflectionRows() {}

        static List<ReflectionQuestion> questions(PreparedStatement ps) throws SQLException {
            try (ResultSet rs = ps.executeQuery()) {
                List<ReflectionQuestion> result = new ArrayList<>();
                while (rs.next()) {
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    result.add(new ReflectionQuestion(
                            rs.getObject("id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getString("text"),
                            createdAt == null ? null : createdAt.toInstant()));
                }
                return result;
            }
        }
    }
}
