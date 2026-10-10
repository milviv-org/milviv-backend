package com.milviv.domain.user;

import java.util.Optional;
import java.util.UUID;

/**
 * Порт доступа к хранилищу пользователей.
 *
 * Живёт в Domain Layer — не знает про JDBC, SQL, транзакции.
 * Реализация находится в Infrastructure Layer (UserJdbcRepository).
 */
public interface UserRepository {
    void save(User user);
    Optional<User> findById(UUID id);
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
