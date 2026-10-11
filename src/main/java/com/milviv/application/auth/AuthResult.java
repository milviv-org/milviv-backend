package com.milviv.application.auth;

import com.milviv.application.user.UserView;

/** Результат регистрации или входа. */
public record AuthResult(UserView user, AuthTokens tokens) {}
