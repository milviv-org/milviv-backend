package com.milviv.domain.sound;

import java.util.Map;
import java.util.UUID;

/**
 * Выбранные пользователем звуки. Нет записи для события — звук по умолчанию,
 * его определяет клиент. Каждый метод фильтрует по userId (AD-14).
 */
public interface SoundSettingsRepository {

    /** Только заданные события. */
    Map<SoundEvent, String> findByUser(UUID userId);

    void set(UUID userId, SoundEvent event, String sound);

    /** Вернуть звук по умолчанию. */
    void reset(UUID userId, SoundEvent event);
}
