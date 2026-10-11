package com.milviv.infrastructure.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.milviv.domain.sound.SoundEvent;
import com.milviv.domain.sound.SoundSettingsRepository;
import com.milviv.infrastructure.exception.DatabaseException;
import com.milviv.infrastructure.persistence.connection.ConnectionProvider;

public class SoundSettingsJdbcRepository implements SoundSettingsRepository {

    private final ConnectionProvider connectionProvider;

    public SoundSettingsJdbcRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public Map<SoundEvent, String> findByUser(UUID userId) {
        String sql = "SELECT event, sound FROM user_sound_settings WHERE user_id = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<SoundEvent, String> result = new EnumMap<>(SoundEvent.class);
                while (rs.next()) {
                    // событие, убранное из перечня, игнорируется, а не роняет чтение
                    try {
                        result.put(SoundEvent.valueOf(rs.getString("event")), rs.getString("sound"));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to read sound settings", ex);
        }
    }

    /**
     * UPDATE, при отсутствии строки — INSERT. Без ON CONFLICT: его нет в H2, на котором идут тесты.
     * Два одновременных первых сохранения одного события дадут нарушение ключа — 503, повтор запроса проходит.
     */
    @Override
    public void set(UUID userId, SoundEvent event, String sound) {
        try (PreparedStatement update = connectionProvider.get().prepareStatement(
                "UPDATE user_sound_settings SET sound = ? WHERE user_id = ? AND event = ?")) {
            update.setString(1, sound);
            update.setObject(2, userId);
            update.setString(3, event.name());
            if (update.executeUpdate() > 0) {
                return;
            }
            try (PreparedStatement insert = connectionProvider.get().prepareStatement(
                    "INSERT INTO user_sound_settings (user_id, event, sound) VALUES (?, ?, ?)")) {
                insert.setObject(1, userId);
                insert.setString(2, event.name());
                insert.setString(3, sound);
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to save sound setting", ex);
        }
    }

    @Override
    public void reset(UUID userId, SoundEvent event) {
        String sql = "DELETE FROM user_sound_settings WHERE user_id = ? AND event = ?";
        try (PreparedStatement ps = connectionProvider.get().prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, event.name());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new DatabaseException("Failed to reset sound setting", ex);
        }
    }
}
