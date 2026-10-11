package com.milviv.infrastructure.http;

/**
 * Проверка, что запрос сделан вошедшим пользователем.
 * Вызывается перед каждым обработчиком, кроме помеченных @Public.
 */
public interface RequestAuthenticator {

    /** Связывает с ctx id пользователя или бросает UnauthorizedException. */
    void authenticate(HttpContext ctx);
}
