package com.milviv.application.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.application.user.UserView;
import com.milviv.domain.exception.UnauthorizedException;
import com.milviv.domain.user.User;
import com.milviv.domain.user.UserRepository;

/**
 * Вход по email и паролю.
 *
 * Ответ одинаков для «нет такого email» и «неверный пароль», и время проверки
 * тоже одинаково (сверка с фиктивным хешем) — по ответу нельзя узнать,
 * зарегистрирован ли email.
 */
public class LoginUser {

    static final String INVALID_CREDENTIALS = "Неверный email или пароль";

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final SessionIssuer sessionIssuer;
    private final LoginAttemptLimiter attemptLimiter;
    private final TransactionManager transactionManager;
    private final Clock clock;
    private final String dummyHash;

    public LoginUser(UserRepository users, PasswordHasher passwordHasher, SessionIssuer sessionIssuer,
                     LoginAttemptLimiter attemptLimiter, TransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.sessionIssuer = sessionIssuer;
        this.attemptLimiter = attemptLimiter;
        this.transactionManager = transactionManager;
        this.clock = clock;
        this.dummyHash = passwordHasher.hash("timing-equalizer-not-a-real-password");
    }

    public AuthResult execute(String email, String password) {
        String normalizedEmail = User.normalizeEmail(email);
        attemptLimiter.checkAllowed(normalizedEmail);

        Optional<User> found = transactionManager.execute(Propagation.SUPPORTS, () -> users.findByEmail(normalizedEmail));

        // Проверка пароля — вне транзакции: BCrypt медленный
        String hashToCheck = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordHasher.matches(password, hashToCheck);

        if (found.isEmpty() || !passwordMatches) {
            attemptLimiter.recordFailure(normalizedEmail);
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }

        attemptLimiter.reset(normalizedEmail);
        User user = found.get();
        Instant now = clock.instant();
        AuthTokens tokens = transactionManager.execute(() -> sessionIssuer.startSession(user.getId(), now));
        return new AuthResult(UserView.from(user), tokens);
    }
}
