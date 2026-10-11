package com.milviv.presentation.rest.controllers;

import java.time.Clock;
import java.time.Instant;

import com.milviv.application.auth.AuthResult;
import com.milviv.application.auth.AuthTokens;
import com.milviv.application.auth.LoginUser;
import com.milviv.application.auth.Logout;
import com.milviv.application.auth.RefreshSession;
import com.milviv.application.auth.RegisterUser;
import com.milviv.application.user.GetCurrentUser;
import com.milviv.application.user.UserView;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.infrastructure.http.annotations.Public;
import com.milviv.infrastructure.security.AuthCookies;
import com.milviv.shared.validation.annotation.annotations.Email;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.MinLength;
import com.milviv.shared.validation.annotation.annotations.Required;

/**
 * Регистрация, вход, обновление токенов, выход.
 * Токены уходят только в HttpOnly-cookie, в теле ответа их нет.
 */
@Controller("/auth")
public class AuthController {

    record RegisterRequest(
            @Required @Email @MaxLength(length = 255)
            String email,

            @Required @MinLength(length = 8) @MaxLength(length = 64)
            String password,

            @Required @MaxLength(length = 100)
            String name
    ) {}

    record LoginRequest(
            @Required @Email @MaxLength(length = 255)
            String email,

            @Required @MaxLength(length = 64)
            String password
    ) {}

    /** accessTokenExpiresAt — чтобы клиент обновил токены заранее, а не после 401. */
    record SessionResponse(UserView user, Instant accessTokenExpiresAt) {}

    record RefreshResponse(Instant accessTokenExpiresAt) {}

    private final RegisterUser registerUser;
    private final LoginUser loginUser;
    private final RefreshSession refreshSession;
    private final Logout logout;
    private final GetCurrentUser getCurrentUser;
    private final AuthCookies cookies;
    private final Clock clock;

    public AuthController(RegisterUser registerUser, LoginUser loginUser, RefreshSession refreshSession,
                          Logout logout, GetCurrentUser getCurrentUser, AuthCookies cookies, Clock clock) {
        this.registerUser = registerUser;
        this.loginUser = loginUser;
        this.refreshSession = refreshSession;
        this.logout = logout;
        this.getCurrentUser = getCurrentUser;
        this.cookies = cookies;
        this.clock = clock;
    }

    @Public
    @Post("/register")
    public void register(HttpContext ctx) throws Exception {
        RegisterRequest body = ctx.body(RegisterRequest.class);
        AuthResult result = registerUser.execute(body.email(), body.password(), body.name());
        respondWithSession(ctx, 201, result);
    }

    @Public
    @Post("/login")
    public void login(HttpContext ctx) throws Exception {
        LoginRequest body = ctx.body(LoginRequest.class);
        AuthResult result = loginUser.execute(body.email(), body.password());
        respondWithSession(ctx, 200, result);
    }

    @Public
    @Post("/refresh")
    public void refresh(HttpContext ctx) throws Exception {
        String refreshToken = cookies.readRefreshToken(ctx.getRequest()).orElse(null);
        try {
            AuthTokens tokens = refreshSession.execute(refreshToken);
            cookies.write(ctx.getResponse(), tokens, clock.instant());
            ctx.json(200, new RefreshResponse(tokens.accessTokenExpiresAt()));
        } catch (RuntimeException ex) {
            // недействительную сессию из браузера убираем, чтобы клиент не повторял запрос
            cookies.clear(ctx.getResponse());
            throw ex;
        }
    }

    /** Публичный: выйти можно и с истёкшим access-токеном. */
    @Public
    @Post("/logout")
    public void logout(HttpContext ctx) {
        logout.execute(cookies.readRefreshToken(ctx.getRequest()).orElse(null));
        cookies.clear(ctx.getResponse());
        ctx.status(204);
    }

    @Get("/me")
    public void me(HttpContext ctx) throws Exception {
        ctx.json(200, getCurrentUser.execute(ctx.userId()));
    }

    private void respondWithSession(HttpContext ctx, int status, AuthResult result) throws Exception {
        cookies.write(ctx.getResponse(), result.tokens(), clock.instant());
        ctx.json(status, new SessionResponse(result.user(), result.tokens().accessTokenExpiresAt()));
    }
}
