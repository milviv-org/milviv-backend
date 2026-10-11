package com.milviv.application.gamification;

import java.util.List;
import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.gamification.PointsEntry;
import com.milviv.domain.gamification.PointsHistoryRepository;

/** Баланс и история баллов. Баланс вычисляется по истории (AD-16). */
public class PointsQueries {

    public static final int MAX_PAGE_SIZE = 100;

    private final PointsHistoryRepository pointsHistory;
    private final HiddenRewardsAccess hiddenAccess;
    private final TransactionManager transactionManager;

    public PointsQueries(PointsHistoryRepository pointsHistory, HiddenRewardsAccess hiddenAccess,
                         TransactionManager transactionManager) {
        this.pointsHistory = pointsHistory;
        this.hiddenAccess = hiddenAccess;
        this.transactionManager = transactionManager;
    }

    public record BalanceView(long balance) {}

    public BalanceView balance(UUID userId) {
        return new BalanceView(transactionManager.execute(Propagation.SUPPORTS, () -> pointsHistory.balance(userId)));
    }

    /** Без верного пароля записи о скрытых наградах обезличены: сумма есть, награды нет. */
    public List<PointsEntryView> history(UUID userId, String hiddenPassword, int limit, int offset) {
        boolean unlocked = hiddenAccess.unlock(userId, hiddenPassword);
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return transactionManager.execute(Propagation.SUPPORTS, () -> pointsHistory.findByUser(userId, pageSize, from))
                .stream()
                .map(entry -> unlocked ? entry : entry.masked())
                .map(PointsEntryView::from)
                .toList();
    }
}
