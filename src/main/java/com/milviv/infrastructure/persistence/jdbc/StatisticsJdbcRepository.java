package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.statistics.StatisticsRepository;
import com.milviv.domain.statistics.WorkProgress;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

/**
 * Прогресс считается одним запросом с GROUP BY. Баллы — из task_completions:
 * это начисленное, даже если баллы задачи потом изменили.
 */
public class StatisticsJdbcRepository implements StatisticsRepository {

    /** Параметры по порядку: сегодня (для просрочки). */
    private static final String PROGRESS_COLUMNS = """
            COUNT(t.id) AS total,
            COALESCE(SUM(CASE WHEN t.status = 'ACTIVE' THEN 1 ELSE 0 END), 0) AS active,
            COALESCE(SUM(CASE WHEN t.status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed,
            COALESCE(SUM(CASE WHEN t.status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled,
            COALESCE(SUM(CASE WHEN t.status = 'ACTIVE' AND t.deadline < ? THEN 1 ELSE 0 END), 0) AS overdue,
            COALESCE(SUM(tc.points), 0) AS points
        """;

    /** Баллы по задаче; параметр — userId. */
    private static final String EARNED_BY_TASK = """
        LEFT JOIN (SELECT task_id, SUM(points) AS points FROM task_completions WHERE user_id = ? GROUP BY task_id) tc
            ON tc.task_id = t.id
        """;

    private final ConnectionProvider connectionProvider;

    public StatisticsJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public List<ProjectProgress> projectProgress(UUID userId, ProjectStatus status, LocalDate today, int limit, int offset) {
        String sql = "SELECT p.id, p.title, p.status, p.deadline, " + PROGRESS_COLUMNS
                + " FROM projects p"
                + " LEFT JOIN tasks t ON t.project_id = p.id AND t.user_id = p.user_id "
                + EARNED_BY_TASK
                + " WHERE p.user_id = ?" + (status != null ? " AND p.status = ?" : "")
                + " GROUP BY p.id, p.title, p.status, p.deadline, p.created_at"
                + " ORDER BY p.created_at DESC, p.id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, today);
            ps.setObject(i++, userId);
            ps.setObject(i++, userId);
            if (status != null) {
                ps.setString(i++, status.name());
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<ProjectProgress> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new ProjectProgress(
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            ProjectStatus.valueOf(rs.getString("status")),
                            rs.getObject("deadline", LocalDate.class),
                            progress(rs)));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to calculate project progress", ex);
        }
    }

    @Override
    public List<GoalProgress> goalProgress(UUID userId, LocalDate today, int limit, int offset) {
        // одно соединение на задачу: задача цели и одновременно её проекта считается один раз
        String sql = "SELECT g.id, g.title, "
                + " (SELECT COUNT(*) FROM projects gp WHERE gp.goal_id = g.id) AS projects, " + PROGRESS_COLUMNS
                + " FROM goals g"
                + " LEFT JOIN tasks t ON t.user_id = g.user_id"
                + "     AND (t.goal_id = g.id OR t.project_id IN (SELECT p.id FROM projects p WHERE p.goal_id = g.id)) "
                + EARNED_BY_TASK
                + " WHERE g.user_id = ?"
                + " GROUP BY g.id, g.title, g.created_at"
                + " ORDER BY g.created_at DESC, g.id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, today);
            ps.setObject(2, userId);
            ps.setObject(3, userId);
            ps.setInt(4, limit);
            ps.setInt(5, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<GoalProgress> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new GoalProgress(
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            rs.getInt("projects"),
                            progress(rs)));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to calculate goal progress", ex);
        }
    }

    private static WorkProgress progress(ResultSet rs) throws SQLException {
        return new WorkProgress(rs.getInt("total"), rs.getInt("active"), rs.getInt("completed"),
                rs.getInt("cancelled"), rs.getInt("overdue"), rs.getLong("points"));
    }
}
