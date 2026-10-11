package com.milviv.application.user;

import java.util.UUID;

import com.milviv.domain.user.User;

/** Данные пользователя, которые можно отдать клиенту. Без хеша пароля. */
public record UserView(UUID id, String email, String name) {

    public static UserView from(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getName());
    }
}
