package com.milviv.domain.user;

import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище пользователей. Реализация — в infrastructure.
 * Методы вызываются внутри TransactionManager.execute().
 */
public interface UserRepository {

    void add(User user);

    Optional<User> findById(UUID id);

    /** email ожидается нормализованным (User.normalizeEmail). */
    Optional<User> findByEmail(String email);

    /** email ожидается нормализованным (User.normalizeEmail). */
    boolean existsByEmail(String email);
}
