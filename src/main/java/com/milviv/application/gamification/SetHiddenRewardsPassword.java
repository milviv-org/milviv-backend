package com.milviv.application.gamification;

import java.util.UUID;

import com.milviv.application.auth.LoginAttemptLimiter;
import com.milviv.application.auth.PasswordHasher;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ForbiddenException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.gamification.HiddenRewardsPasswordRepository;
import com.milviv.domain.user.User;
import com.milviv.domain.user.UserRepository;

/**
 * Задать или сменить пароль скрытых наград. Подтверждается паролем аккаунта:
 * кто сидит в чужой открытой сессии, не знает его и не сможет сменить пароль.
 * Забытый пароль скрытых наград восстанавливается так же — паролем аккаунта.
 *
 * Неверный пароль аккаунта расходует попытки входа (общий лимит по email).
 */
public class SetHiddenRewardsPassword {

    private final UserRepository users;
    private final HiddenRewardsPasswordRepository passwords;
    private final PasswordHasher passwordHasher;
    private final LoginAttemptLimiter attemptLimiter;
    private final TransactionManager transactionManager;

    public SetHiddenRewardsPassword(UserRepository users, HiddenRewardsPasswordRepository passwords,
                                    PasswordHasher passwordHasher, LoginAttemptLimiter attemptLimiter,
                                    TransactionManager transactionManager) {
        this.users = users;
        this.passwords = passwords;
        this.passwordHasher = passwordHasher;
        this.attemptLimiter = attemptLimiter;
        this.transactionManager = transactionManager;
    }

    public void execute(UUID userId, String accountPassword, String newPassword) {
        User user = transactionManager.execute(Propagation.SUPPORTS, () -> users.findById(userId))
                .orElseThrow(() -> new NotFoundException("Пользователь не найден"));

        attemptLimiter.checkAllowed(user.getEmail());
        if (!passwordHasher.matches(accountPassword, user.getPasswordHash())) {
            attemptLimiter.recordFailure(user.getEmail());
            // 403, а не 401: клиент не должен пытаться обновить токен
            throw new ForbiddenException("Неверный пароль аккаунта");
        }
        attemptLimiter.reset(user.getEmail());

        String hash = passwordHasher.hash(newPassword);
        transactionManager.execute(() -> passwords.setHash(userId, hash));
    }

    public boolean isSet(UUID userId) {
        return transactionManager.execute(Propagation.SUPPORTS, () -> passwords.findHash(userId)).isPresent();
    }
}
