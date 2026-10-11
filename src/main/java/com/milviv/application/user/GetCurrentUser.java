package com.milviv.application.user;

import java.util.UUID;

import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.UnauthorizedException;
import com.milviv.domain.user.UserRepository;

/** Данные текущего пользователя. */
public class GetCurrentUser {

    private final UserRepository users;
    private final TransactionManager transactionManager;

    public GetCurrentUser(UserRepository users, TransactionManager transactionManager) {
        this.users = users;
        this.transactionManager = transactionManager;
    }

    public UserView execute(UUID userId) {
        return transactionManager.execute(Propagation.SUPPORTS, () -> users.findById(userId))
                .map(UserView::from)
                // токен ещё действует, а пользователя уже нет
                .orElseThrow(() -> new UnauthorizedException("Пользователь не найден"));
    }
}
