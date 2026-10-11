package com.milviv.application.auth;

import java.time.Duration;

/**
 * Сроки жизни токенов.
 *
 * @param accessTokenTtl     срок access-токена
 * @param refreshTokenTtl    срок refresh-токена; продлевается при каждом обновлении
 * @param sessionMaxLifetime абсолютный срок сессии от входа — после него нужен новый вход
 */
public record AuthPolicy(Duration accessTokenTtl, Duration refreshTokenTtl, Duration sessionMaxLifetime) {}
