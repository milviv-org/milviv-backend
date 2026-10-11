package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.milviv.domain.ai.AiRequest;
import com.milviv.domain.ai.AiRequestRepository;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.OperationReport;
import com.milviv.domain.ai.RefusalReason;
import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

/**
 * result (отчёт) — JSONB, пишется через CAST(? AS JSONB). Остальные JSONB-колонки
 * (handles, plan, decision, data_access_log) появятся в подэтапах 9.4–9.5.
 */
public class AiRequestJdbcRepository implements AiRequestRepository {

    private static final String COLUMNS = "id, user_id, status, idempotency_key, request_hash, message_length, "
            + "message_hash, summary, answer, clarification_question, refusal_reason, error_code, contract_version, "
            + "prompt_version, provider, model, ai_calls, input_tokens, output_tokens, latency_ms, result, created_at, "
            + "executed_at, version";

    private static final TypeReference<List<OperationReport.Item>> REPORT_ITEMS = new TypeReference<>() {};

    private final ConnectionProvider connectionProvider;
    private final ObjectMapper objectMapper;

    public AiRequestJdbcRepository(ConnectionProvider connectionProvider, ObjectMapper objectMapper) {
        this.connectionProvider = connectionProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void lockUser(UUID userId) {
        try (PreparedStatement ps = connectionProvider.get().prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, userId);
            ps.executeQuery().close();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to lock user for AI request", ex);
        }
    }

    @Override
    public void add(AiRequest request) {
        String sql = "INSERT INTO ai_requests (" + COLUMNS + ") VALUES "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, request.getId());
            ps.setObject(2, request.getUserId());
            ps.setString(3, request.getStatus().name());
            ps.setString(4, request.getIdempotencyKey());
            ps.setString(5, request.getRequestHash());
            ps.setInt(6, request.getMessageLength());
            ps.setString(7, request.getMessageHash());
            ps.setString(8, request.getSummary());
            ps.setString(9, request.getAnswer());
            ps.setString(10, request.getClarificationQuestion());
            ps.setString(11, name(request.getRefusalReason()));
            ps.setString(12, request.getErrorCode());
            ps.setString(13, request.getContractVersion());
            ps.setString(14, request.getPromptVersion());
            ps.setString(15, request.getProvider());
            ps.setString(16, request.getModel());
            ps.setInt(17, request.getAiCalls());
            setInteger(ps, 18, request.getInputTokens());
            setInteger(ps, 19, request.getOutputTokens());
            setInteger(ps, 20, request.getLatencyMs());
            ps.setString(21, toJson(request.getReport()));
            ps.setTimestamp(22, Timestamp.from(request.getCreatedAt()));
            ps.setTimestamp(23, timestamp(request.getExecutedAt()));
            ps.setLong(24, request.getVersion());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert AI request", ex);
        }
    }

    @Override
    public Optional<AiRequest> findById(UUID userId, UUID requestId) {
        return findOne("id = ?", userId, requestId);
    }

    @Override
    public Optional<AiRequest> findByIdempotencyKey(UUID userId, String idempotencyKey) {
        return findOne("idempotency_key = ?", userId, idempotencyKey);
    }

    @Override
    public Optional<AiRequest> findPending(UUID userId) {
        return findOne("status = ?", userId, AiRequestStatus.PENDING_AI.name());
    }

    private Optional<AiRequest> findOne(String condition, UUID userId, Object value) {
        String sql = "SELECT " + COLUMNS + " FROM ai_requests WHERE user_id = ? AND " + condition;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, value);
            List<AiRequest> found = list(ps);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find AI request", ex);
        }
    }

    @Override
    public List<AiRequest> findByUser(UUID userId, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM ai_requests WHERE user_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return list(ps);
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to list AI requests", ex);
        }
    }

    @Override
    public void update(AiRequest request) {
        String sql = """
            UPDATE ai_requests
            SET status = ?, summary = ?, answer = ?, clarification_question = ?, refusal_reason = ?, error_code = ?,
                ai_calls = ?, input_tokens = ?, output_tokens = ?, latency_ms = ?, result = CAST(? AS JSONB),
                executed_at = ?, version = version + 1
            WHERE id = ? AND user_id = ? AND version = ?
            """;
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, request.getStatus().name());
            ps.setString(2, request.getSummary());
            ps.setString(3, request.getAnswer());
            ps.setString(4, request.getClarificationQuestion());
            ps.setString(5, name(request.getRefusalReason()));
            ps.setString(6, request.getErrorCode());
            ps.setInt(7, request.getAiCalls());
            setInteger(ps, 8, request.getInputTokens());
            setInteger(ps, 9, request.getOutputTokens());
            setInteger(ps, 10, request.getLatencyMs());
            ps.setString(11, toJson(request.getReport()));
            ps.setTimestamp(12, timestamp(request.getExecutedAt()));
            ps.setObject(13, request.getId());
            ps.setObject(14, request.getUserId());
            ps.setLong(15, request.getVersion());
            if (ps.executeUpdate() == 0) {
                throw new OptimisticLockException("AiRequest", request.getId(), request.getVersion());
            }
            request.incrementVersion();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to update AI request", ex);
        }
    }

    private List<AiRequest> list(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<AiRequest> result = new ArrayList<>();
            while (rs.next()) {
                String refusal = rs.getString("refusal_reason");
                result.add(new AiRequest(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        AiRequestStatus.valueOf(rs.getString("status")),
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getInt("message_length"),
                        rs.getString("message_hash"),
                        rs.getString("summary"),
                        rs.getString("answer"),
                        rs.getString("clarification_question"),
                        refusal == null ? null : RefusalReason.valueOf(refusal),
                        rs.getString("error_code"),
                        rs.getString("contract_version"),
                        rs.getString("prompt_version"),
                        rs.getString("provider"),
                        rs.getString("model"),
                        rs.getInt("ai_calls"),
                        rs.getObject("input_tokens", Integer.class),
                        rs.getObject("output_tokens", Integer.class),
                        rs.getObject("latency_ms", Integer.class),
                        fromJson(rs.getString("result")),
                        rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("executed_at")),
                        rs.getLong("version")));
            }
            return result;
        }
    }

    private String toJson(OperationReport report) {
        if (report == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(report.items());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize AI operation report", ex);
        }
    }

    private OperationReport fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return new OperationReport(objectMapper.readValue(json, REPORT_ITEMS));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read AI operation report", ex);
        }
    }

    private static String name(RefusalReason reason) {
        return reason == null ? null : reason.name();
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
