package com.milviv.application.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.milviv.application.transaction.TransactionManager;
import com.milviv.application.user.UserView;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.user.User;
import com.milviv.domain.user.UserRepository;

/**
 * Регистрация: создать пользователя и сразу начать сессию.
 *
 * Длина и формат полей проверяются на входе (DTO). Здесь — правила,
 * которые декларативно не выразить: лимит BCrypt и уникальность email.
 */
public class RegisterUser {

    /** BCrypt учитывает только первые 72 байта пароля — длиннее молча обрезался бы. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final SessionIssuer sessionIssuer;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public RegisterUser(UserRepository users, PasswordHasher passwordHasher, SessionIssuer sessionIssuer,
                        TransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.sessionIssuer = sessionIssuer;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public AuthResult execute(String email, String password, String name) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ValidationException("password", "Пароль слишком длинный");
        }

        // Хеширование медленное (сотни миллисекунд) — вне транзакции, чтобы не держать соединение
        String passwordHash = passwordHasher.hash(password);
        Instant now = clock.instant();
        User user = User.register(email, name, passwordHash, now);

        return transactionManager.execute(() -> {
            if (users.existsByEmail(user.getEmail())) {
                throw new ConflictException("Пользователь с таким email уже существует");
            }
            users.add(user);
            AuthTokens tokens = sessionIssuer.startSession(user.getId(), now);
            return new AuthResult(UserView.from(user), tokens);
        });
    }
}
