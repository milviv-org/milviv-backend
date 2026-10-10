package com.milviv.infrastructure.container;

/**
 * Область создания класса.
 * SINGLETONE - каждый вызов `container.get` один и тот же инстанс класса 
 * PROTOTYPE - каждый вызов `container.get` новый инстанс класса 
 */
public enum Scope {
    SINGLETON,
    PROTOTYPE
}
