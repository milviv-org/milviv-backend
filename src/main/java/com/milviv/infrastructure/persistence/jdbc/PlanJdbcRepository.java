package com.milviv.infrastructure.persistence.jdbc;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.milviv.domain.planning.Plan;
import com.milviv.domain.planning.PlanRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

/** days_of_week — SMALLINT[] с номерами ISO: 1 = понедельник … 7 = воскресенье (DayOfWeek.getValue). */
public class PlanJdbcRepository implements PlanRepository {

    private static final String COLUMNS = "id, user_id, task_id, title, description, plan_date, days_of_week, "
            + "time_from, time_to, created_at, updated_at";

    private final ConnectionProvider connectionProvider;

    public PlanJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(Plan plan) {
        String sql = "INSERT INTO plans (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        Connection connection = connectionProvider.get();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, plan.getId());
            ps.setObject(2, plan.getUserId());
            ps.setObject(3, plan.getTaskId());
            ps.setString(4, plan.getTitle());
            ps.setString(5, plan.getDescription());
            ps.setObject(6, plan.getPlanDate());
            ps.setArray(7, toArray(connection, plan.getDaysOfWeek()));
            ps.setObject(8, plan.getTimeFrom());
            ps.setObject(9, plan.getTimeTo());
            ps.setTimestamp(10, timestamp(plan.getCreatedAt()));
            ps.setTimestamp(11, timestamp(plan.getUpdatedAt()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert plan", ex);
        }
    }

    @Override
    public Optional<Plan> findById(UUID userId, UUID planId) {
        return findOne(userId, planId, "");
    }

    @Override
    public Optional<Plan> findByIdForUpdate(UUID userId, UUID planId) {
        return findOne(userId, planId, " FOR UPDATE");
    }

    private Optional<Plan> findOne(UUID userId, UUID planId, String lockClause) {
        String sql = "SELECT " + COLUMNS + " FROM plans WHERE id = ? AND user_id = ?" + lockClause;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, planId);
            ps.setObject(2, userId);
            List<Plan> found = list(ps);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find plan", ex);
        }
    }

    @Override
    public List<Plan> findByUser(UUID userId, UUID taskId, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM plans WHERE user_id = ?"
                + (taskId != null ? " AND task_id = ?" : "")
                + " ORDER BY plan_date NULLS LAST, time_from, id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, userId);
            if (taskId != null) {
                ps.setObject(i++, taskId);
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            return list(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list plans", ex);
        }
    }

    @Override
    public List<Plan> findForPeriod(UUID userId, LocalDate from, LocalDate to) {
        String sql = "SELECT " + COLUMNS + " FROM plans WHERE user_id = ?"
                + " AND (plan_date BETWEEN ? AND ? OR days_of_week IS NOT NULL)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, from);
            ps.setObject(3, to);
            return list(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list plans for period", ex);
        }
    }

    @Override
    public Optional<LocalDate> findLatestPlanDateForTask(UUID userId, UUID taskId) {
        String sql = "SELECT MAX(plan_date) FROM plans WHERE user_id = ? AND task_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, LocalDate.class));
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find latest plan date", ex);
        }
    }

    @Override
    public void update(Plan plan) {
        String sql = """
            UPDATE plans
            SET task_id = ?, title = ?, description = ?, plan_date = ?, days_of_week = ?, time_from = ?, time_to = ?,
                updated_at = ?
            WHERE id = ? AND user_id = ?
            """;
        Connection connection = connectionProvider.get();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, plan.getTaskId());
            ps.setString(2, plan.getTitle());
            ps.setString(3, plan.getDescription());
            ps.setObject(4, plan.getPlanDate());
            ps.setArray(5, toArray(connection, plan.getDaysOfWeek()));
            ps.setObject(6, plan.getTimeFrom());
            ps.setObject(7, plan.getTimeTo());
            ps.setTimestamp(8, timestamp(plan.getUpdatedAt()));
            ps.setObject(9, plan.getId());
            ps.setObject(10, plan.getUserId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update plan", ex);
        }
    }

    @Override
    public boolean delete(UUID userId, UUID planId) {
        String sql = "DELETE FROM plans WHERE id = ? AND user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, planId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to delete plan", ex);
        }
    }

    private List<Plan> list(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Plan> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        }
    }

    private Plan mapRow(ResultSet rs) throws SQLException {
        return new Plan(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getObject("plan_date", LocalDate.class),
                fromArray(rs.getArray("days_of_week")),
                rs.getObject("time_from", LocalTime.class),
                rs.getObject("time_to", LocalTime.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private static Array toArray(Connection connection, Set<DayOfWeek> days) throws SQLException {
        if (days == null) {
            return null;
        }
        Short[] values = days.stream().map(day -> (short) day.getValue()).toArray(Short[]::new);
        return connection.createArrayOf("smallint", values);
    }

    private static Set<DayOfWeek> fromArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (Object value : (Object[]) array.getArray()) {
            days.add(DayOfWeek.of(((Number) value).intValue()));
        }
        return days;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
