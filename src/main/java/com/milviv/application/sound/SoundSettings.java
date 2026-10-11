package com.milviv.application.sound;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.sound.SoundEvent;
import com.milviv.domain.sound.SoundSettingsRepository;

/**
 * Настройки звуков. Сервер хранит только ключ звука: файлы и каталог звуков — на клиенте,
 * поэтому проверяется лишь формат ключа.
 */
public class SoundSettings {

    private final SoundSettingsRepository settings;
    private final TransactionManager transactionManager;

    public SoundSettings(SoundSettingsRepository settings, TransactionManager transactionManager) {
        this.settings = settings;
        this.transactionManager = transactionManager;
    }

    /** @param sound null — звук по умолчанию */
    public record SoundSettingView(SoundEvent event, String sound) {}

    /** Все события, в порядке объявления; у незаданных sound = null. */
    public List<SoundSettingView> get(UUID userId) {
        Map<SoundEvent, String> chosen = transactionManager.execute(Propagation.SUPPORTS, () -> settings.findByUser(userId));
        return Arrays.stream(SoundEvent.values())
                .map(event -> new SoundSettingView(event, chosen.get(event)))
                .toList();
    }

    public List<SoundSettingView> set(UUID userId, SoundEvent event, String sound) {
        transactionManager.execute(() -> settings.set(userId, event, sound));
        return get(userId);
    }

    public List<SoundSettingView> reset(UUID userId, SoundEvent event) {
        transactionManager.execute(() -> settings.reset(userId, event));
        return get(userId);
    }
}
