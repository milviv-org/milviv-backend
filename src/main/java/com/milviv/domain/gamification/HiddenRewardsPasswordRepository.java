package com.milviv.domain.gamification;

import java.util.Optional;
import java.util.UUID;

/** Пароль скрытых наград — один на пользователя (G-18). Хранится только хеш. */
public interface HiddenRewardsPasswordRepository {

    /** Пусто — пароль ещё не задан. */
    Optional<String> findHash(UUID userId);

    void setHash(UUID userId, String passwordHash);
}
