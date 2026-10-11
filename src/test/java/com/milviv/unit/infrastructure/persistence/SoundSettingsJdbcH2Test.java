package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.sound.SoundSettings;
import com.milviv.application.sound.SoundSettings.SoundSettingView;
import com.milviv.domain.sound.SoundEvent;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.SoundSettingsJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;

/** Звуки на H2: все события в ответе, сохранение, замена, сброс, изоляция. */
class SoundSettingsJdbcH2Test {

    private Connection keepAlive;
    private SoundSettings sounds;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sounds" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        try (Statement st = keepAlive.createStatement()) {
            H2WorkSchema.create(st);
        }
        sounds = new SoundSettings(new SoundSettingsJdbcRepository(new TransactionalConnectionProvider()),
                new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1));
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    @Test
    void get_shouldListEveryEvent_withDefaultsAsNull() {
        assertThat(sounds.get(alice)).extracting(SoundSettingView::event).containsExactly(SoundEvent.values());
        assertThat(sounds.get(alice)).allMatch(item -> item.sound() == null);
    }

    @Test
    void set_replace_andReset() {
        sounds.set(alice, SoundEvent.TASK_COMPLETED, "fanfare");
        sounds.set(alice, SoundEvent.TASK_COMPLETED, "level-up.v2");
        sounds.set(alice, SoundEvent.TASK_REMINDER, "bell");

        assertThat(sound(alice, SoundEvent.TASK_COMPLETED)).isEqualTo("level-up.v2");
        assertThat(sound(alice, SoundEvent.TASK_REMINDER)).isEqualTo("bell");
        assertThat(sound(bob, SoundEvent.TASK_COMPLETED)).isNull();

        sounds.reset(alice, SoundEvent.TASK_COMPLETED);
        assertThat(sound(alice, SoundEvent.TASK_COMPLETED)).isNull();
        assertThat(sound(alice, SoundEvent.TASK_REMINDER)).isEqualTo("bell");
    }

    @Test
    void unknownStoredEvent_shouldBeIgnored() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("INSERT INTO user_sound_settings VALUES ('" + alice + "', 'REMOVED_EVENT', 'x')");
        }
        assertThat(sounds.get(alice)).hasSize(SoundEvent.values().length);
    }

    private String sound(java.util.UUID user, SoundEvent event) {
        return sounds.get(user).stream().filter(item -> item.event() == event).findFirst().orElseThrow().sound();
    }
}
