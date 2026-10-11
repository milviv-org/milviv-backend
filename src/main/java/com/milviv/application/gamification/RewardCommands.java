package com.milviv.application.gamification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.ForbiddenException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.gamification.PointsHistoryRepository;
import com.milviv.domain.gamification.Reward;
import com.milviv.domain.gamification.RewardDetails;
import com.milviv.domain.gamification.RewardRepository;

/**
 * Сценарии работы с наградой и её получение за баллы.
 *
 * Видимость: скрытая награда без верного пароля неотличима от несуществующей — 404,
 * как и чужая. hiddenPassword — пароль из запроса или null.
 */
public class RewardCommands {

    public static final int MAX_PAGE_SIZE = 100;

    private final RewardRepository rewards;
    private final PointsHistoryRepository pointsHistory;
    private final HiddenRewardsAccess hiddenAccess;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public RewardCommands(RewardRepository rewards, PointsHistoryRepository pointsHistory, HiddenRewardsAccess hiddenAccess,
                          TransactionManager transactionManager, Clock clock) {
        this.rewards = rewards;
        this.pointsHistory = pointsHistory;
        this.hiddenAccess = hiddenAccess;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    /** @param balance баланс после списания */
    public record RedeemResult(RewardView reward, int pointsSpent, long balance) {}

    public RewardView create(UUID userId, RewardDetails details, String hiddenPassword) {
        if (details.hidden()) {
            hiddenAccess.require(userId, hiddenPassword);
        }
        Reward reward = Reward.create(userId, details, clock.instant());
        transactionManager.execute(() -> rewards.add(reward));
        return RewardView.from(reward);
    }

    public RewardView get(UUID userId, UUID rewardId, String hiddenPassword) {
        boolean unlocked = hiddenAccess.unlock(userId, hiddenPassword);
        return RewardView.from(transactionManager.execute(Propagation.SUPPORTS, () -> load(userId, rewardId, unlocked)));
    }

    /** С верным паролем — вместе со скрытыми. */
    public List<RewardView> list(UUID userId, String hiddenPassword, int limit, int offset) {
        boolean unlocked = hiddenAccess.unlock(userId, hiddenPassword);
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return transactionManager.execute(Propagation.SUPPORTS, () -> rewards.findByUser(userId, unlocked, pageSize, from))
                .stream()
                .map(RewardView::from)
                .toList();
    }

    /**
     * Скрыть видимую награду можно только с паролем. Изменить скрытую — тоже:
     * без пароля её не найти.
     *
     * @param expectedVersion версия, которую видел клиент; изменилась — 409
     */
    public RewardView update(UUID userId, UUID rewardId, RewardDetails details, long expectedVersion, String hiddenPassword) {
        boolean unlocked = hiddenAccess.unlock(userId, hiddenPassword);
        if (details.hidden() && !unlocked) {
            throw new ForbiddenException("Нужен пароль скрытых наград");
        }
        Instant now = clock.instant();
        return RewardView.from(transactionManager.execute(() -> {
            Reward reward = load(userId, rewardId, unlocked);
            if (reward.getVersion() != expectedVersion) {
                throw new ConflictException("Награда была изменена. Обновите данные и повторите");
            }
            reward.edit(details, now);
            rewards.update(reward);
            return reward;
        }));
    }

    /** История баллов остаётся. */
    public void delete(UUID userId, UUID rewardId, String hiddenPassword) {
        boolean unlocked = hiddenAccess.unlock(userId, hiddenPassword);
        transactionManager.execute(() -> {
            load(userId, rewardId, unlocked);
            rewards.delete(userId, rewardId);
        });
    }

    /**
     * Получить награду: списать её стоимость. Баланс не может уйти в минус (QG-03):
     * баланс пользователя заблокирован на время проверки и записи.
     */
    public RedeemResult redeem(UUID userId, UUID rewardId, String hiddenPassword) {
        boolean unlocked = hiddenAccess.unlock(userId, hiddenPassword);
        Instant now = clock.instant();
        return transactionManager.execute(() -> {
            pointsHistory.lockBalance(userId);
            Reward reward = load(userId, rewardId, unlocked);
            long balance = pointsHistory.balance(userId);
            if (balance < reward.getCost()) {
                throw new ConflictException("Недостаточно баллов: есть " + balance + ", нужно " + reward.getCost());
            }
            pointsHistory.recordRewardRedeemed(userId, reward.getId(), reward.getCost(), reward.redemptionReason(), now);
            return new RedeemResult(RewardView.from(reward), reward.getCost(), balance - reward.getCost());
        });
    }

    private Reward load(UUID userId, UUID rewardId, boolean unlocked) {
        return rewards.findById(userId, rewardId)
                .filter(reward -> unlocked || !reward.isHidden())
                .orElseThrow(() -> new NotFoundException("Награда не найдена"));
    }
}
