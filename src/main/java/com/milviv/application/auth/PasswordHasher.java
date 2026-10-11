package com.milviv.application.auth;

/** Хеширование паролей. Реализация — в infrastructure (BCrypt). */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
