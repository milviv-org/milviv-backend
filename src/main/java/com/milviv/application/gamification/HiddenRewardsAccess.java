package com.milviv.application.gamification;

import java.util.UUID;

import com.milviv.application.auth.LoginAttemptLimiter;
import com.milviv.application.auth.PasswordHasher;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ForbiddenException;
import com.milviv.domain.gamification.HiddenRewardsPasswordRepository;

/**
 * Проверка пароля скрытых наград (G-18). Пароль приходит с каждым запросом, который
 * должен видеть скрытые награды; сервер его не запоминает.
 *
 * Неверные попытки ограничены тем же лимитом, что и вход: украденная сессия
 * не позволяет перебирать пароль.
 *
 * Вызывается до транзакции сценария: BCrypt медленный, соединение из пула на это время не держим.
 */
public class HiddenRewardsAccess {

    private final HiddenRewardsPasswordRepository passwords;
    private final PasswordHasher passwordHasher;
    private final LoginAttemptLimiter attemptLimiter;
    private final TransactionManager transactionManager;

    public HiddenRewardsAccess(HiddenRewardsPasswordRepository passwords, PasswordHasher passwordHasher,
                               LoginAttemptLimiter attemptLimiter, TransactionManager transactionManager) {
        this.passwords = passwords;
        this.passwordHasher = passwordHasher;
        this.attemptLimiter = attemptLimiter;
        this.transactionManager = transactionManager;
    }

    /**
     * @param password пароль из запроса; null — скрытые награды не нужны
     * @return true, если пароль передан и верен
     * @throws ForbiddenException пароль передан, но неверен или не задан
     */
    public boolean unlock(UUID userId, String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        String key = attemptKey(userId);
        attemptLimiter.checkAllowed(key);

        String hash = transactionManager.execute(Propagation.SUPPORTS, () -> passwords.findHash(userId))
                .orElseThrow(() -> new ForbiddenException("Пароль скрытых наград не задан"));
        if (!passwordHasher.matches(password, hash)) {
            attemptLimiter.recordFailure(key);
            throw new ForbiddenException("Неверный пароль скрытых наград");
        }
        attemptLimiter.reset(key);
        return true;
    }

    /** Для операций, которые без пароля невозможны: создать скрытую награду, скрыть видимую. */
    public void require(UUID userId, String password) {
        if (!unlock(userId, password)) {
            throw new ForbiddenException("Нужен пароль скрытых наград");
        }
    }

    private static String attemptKey(UUID userId) {
        // email не может содержать «:» вне кавычек — с ключами входа не пересекается
        return "hidden-rewards:" + userId;
    }
}
