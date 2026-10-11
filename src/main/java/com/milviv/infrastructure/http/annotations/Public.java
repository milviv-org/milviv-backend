package com.milviv.infrastructure.http.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маршрут доступен без входа.
 * По умолчанию все маршруты требуют входа (AR-05) — открытые помечаются явно.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Public {
}
