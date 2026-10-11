package com.milviv.infrastructure.security;

import org.mindrot.jbcrypt.BCrypt;

import com.milviv.application.auth.PasswordHasher;

/** BCrypt с солью на каждый пароль. Cost задаёт число раундов: 2^cost. */
public class BCryptPasswordHasher implements PasswordHasher {

    private final int cost;

    public BCryptPasswordHasher(int cost) {
        this.cost = cost;
    }

    @Override
    public String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(cost));
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        try {
            return BCrypt.checkpw(rawPassword, passwordHash);
        } catch (IllegalArgumentException ex) {
            // повреждённый хеш — не совпадение, а не 500
            return false;
        }
    }
}
