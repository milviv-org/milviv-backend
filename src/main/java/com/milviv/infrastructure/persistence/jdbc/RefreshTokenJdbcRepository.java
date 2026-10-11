package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.milviv.domain.auth.RefreshToken;
import com.milviv.domain.auth.RefreshTokenRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class RefreshTokenJdbcRepository implements RefreshTokenRepository {

    private static final String COLUMNS =
            "id, user_id, family_id, token_hash, created_at, expires_at, session_expires_at, revoked_at";

    private final ConnectionProvider connectionProvider;

    public RefreshTokenJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public void add(RefreshToken token) {
        String sql = "INSERT INTO refresh_tokens (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, token.getId());
            ps.setObject(2, token.getUserId());
            ps.setObject(3, token.getFamilyId());
            ps.setString(4, token.getTokenHash());
            ps.setTimestamp(5, Timestamp.from(token.getCreatedAt()));
            ps.setTimestamp(6, Timestamp.from(token.getExpiresAt()));
            ps.setTimestamp(7, Timestamp.from(token.getSessionExpiresAt()));
            ps.setTimestamp(8, token.getRevokedAt() == null ? null : Timestamp.from(token.getRevokedAt()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to insert refresh token", ex);
        }
    }

    @Override
    public Optional<RefreshToken> findByHashForUpdate(String tokenHash) {
        String sql = "SELECT " + COLUMNS + " FROM refresh_tokens WHERE token_hash = ? FOR UPDATE";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to find refresh token", ex);
        }
    }

    @Override
    public void revoke(UUID tokenId, Instant revokedAt) {
        update("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", revokedAt, tokenId);
    }

    @Override
    public void revokeFamily(UUID familyId, Instant revokedAt) {
        update("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", revokedAt, familyId);
    }

    private void update(String sql, Instant revokedAt, UUID id) {
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(revokedAt));
            ps.setObject(2, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to revoke refresh token", ex);
        }
    }

    private RefreshToken mapRow(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new RefreshToken(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("family_id", UUID.class),
                rs.getString("token_hash"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("session_expires_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant()
        );
    }
}
