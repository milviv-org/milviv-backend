package com.milviv.unit.application.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.auth.AccessTokenIssuer;
import com.milviv.application.auth.AuthPolicy;
import com.milviv.application.auth.AuthResult;
import com.milviv.application.auth.AuthTokens;
import com.milviv.application.auth.LoginAttemptLimiter;
import com.milviv.application.auth.LoginUser;
import com.milviv.application.auth.Logout;
import com.milviv.application.auth.PasswordHasher;
import com.milviv.application.auth.RefreshSession;
import com.milviv.application.auth.RefreshTokenCodec;
import com.milviv.application.auth.RegisterUser;
import com.milviv.application.auth.SessionIssuer;
import com.milviv.application.transaction.IsolationLevel;
import com.milviv.application.transaction.Propagation;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.application.user.GetCurrentUser;
import com.milviv.domain.auth.RefreshToken;
import com.milviv.domain.auth.RefreshTokenRepository;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.TooManyRequestsException;
import com.milviv.domain.exception.UnauthorizedException;
import com.milviv.domain.exception.ValidationException;
import com.milviv.domain.user.User;
import com.milviv.domain.user.UserRepository;

class AuthUseCasesTest {

    private static final Instant START = Instant.parse("2026-10-10T12:00:00Z");
    private static final AuthPolicy POLICY = new AuthPolicy(Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofDays(90));

    private MutableClock clock;
    private InMemoryUsers users;
    private InMemoryRefreshTokens refreshTokens;
    private CountingLimiter limiter;
    private RegisterUser registerUser;
    private LoginUser loginUser;
    private RefreshSession refreshSession;
    private Logout logout;
    private GetCurrentUser getCurrentUser;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        users = new InMemoryUsers();
        refreshTokens = new InMemoryRefreshTokens();
        limiter = new CountingLimiter(3);
        TransactionManager tx = new DirectTransactionManager();
        PasswordHasher hasher = new PrefixHasher();
        AccessTokenIssuer accessTokens = (userId, now) ->
                new AccessTokenIssuer.AccessToken("access-" + userId + "-" + now.getEpochSecond(), now.plus(POLICY.accessTokenTtl()));
        SessionIssuer sessions = new SessionIssuer(refreshTokens, accessTokens, POLICY);

        registerUser = new RegisterUser(users, hasher, sessions, tx, clock);
        loginUser = new LoginUser(users, hasher, sessions, limiter, tx, clock);
        refreshSession = new RefreshSession(refreshTokens, sessions, tx, clock);
        logout = new Logout(refreshTokens, tx, clock);
        getCurrentUser = new GetCurrentUser(users, tx);
    }

    // ─── Регистрация ──────────────────────────────────────────────────────────

    @Test
    void register_shouldCreateUserWithNormalizedEmail_andStartSession() {
        AuthResult result = registerUser.execute("  Abay@Example.COM ", "password123", " Абай ");

        assertThat(result.user().email()).isEqualTo("abay@example.com");
        assertThat(result.user().name()).isEqualTo("Абай");
        assertThat(users.byEmail("abay@example.com").getPasswordHash()).isEqualTo("hashed:password123");
        assertThat(result.tokens().refreshToken()).isNotBlank();
        assertThat(refreshTokens.active()).hasSize(1);
        assertThat(result.tokens().refreshTokenExpiresAt()).isEqualTo(START.plus(Duration.ofDays(30)));
    }

    @Test
    void register_shouldRejectDuplicateEmail_caseInsensitive() {
        registerUser.execute("abay@example.com", "password123", "Абай");

        assertThatThrownBy(() -> registerUser.execute("ABAY@example.com", "password456", "Другой"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void register_shouldRejectPasswordLongerThan72Bytes() {
        String password = "пароль".repeat(7); // 42 символа, 84 байта UTF-8

        assertThatThrownBy(() -> registerUser.execute("a@b.c", password, "A"))
                .isInstanceOf(ValidationException.class);
        assertThat(users.count()).isZero();
    }

    // ─── Вход ─────────────────────────────────────────────────────────────────

    @Test
    void login_shouldStartNewSession_forValidCredentials() {
        registerUser.execute("abay@example.com", "password123", "Абай");

        AuthResult result = loginUser.execute("ABAY@example.com", "password123");

        assertThat(result.user().email()).isEqualTo("abay@example.com");
        assertThat(refreshTokens.active()).hasSize(2); // сессия регистрации + сессия входа
    }

    @Test
    void login_shouldGiveSameError_forWrongPasswordAndUnknownEmail() {
        registerUser.execute("abay@example.com", "password123", "Абай");

        assertThatThrownBy(() -> loginUser.execute("abay@example.com", "wrong-password"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Неверный email или пароль");
        assertThatThrownBy(() -> loginUser.execute("nobody@example.com", "password123"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Неверный email или пароль");
    }

    @Test
    void login_shouldBlockAfterTooManyFailures_evenWithCorrectPassword() {
        registerUser.execute("abay@example.com", "password123", "Абай");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> loginUser.execute("abay@example.com", "wrong")).isInstanceOf(UnauthorizedException.class);
        }

        assertThatThrownBy(() -> loginUser.execute("abay@example.com", "password123"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void login_shouldResetFailures_afterSuccess() {
        registerUser.execute("abay@example.com", "password123", "Абай");
        assertThatThrownBy(() -> loginUser.execute("abay@example.com", "wrong")).isInstanceOf(UnauthorizedException.class);

        loginUser.execute("abay@example.com", "password123");

        assertThat(limiter.failures("abay@example.com")).isZero();
    }

    // ─── Обновление ───────────────────────────────────────────────────────────

    @Test
    void refresh_shouldRotateToken_andExtendExpiry() {
        AuthTokens first = registerUser.execute("a@b.c", "password123", "A").tokens();
        clock.advance(Duration.ofDays(10));

        AuthTokens second = refreshSession.execute(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.refreshTokenExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        assertThat(refreshTokens.active()).hasSize(1);
        assertThat(refreshTokens.byRaw(first.refreshToken()).isRevoked()).isTrue();
    }

    @Test
    void refresh_shouldRevokeWholeSession_whenOldTokenReused() {
        // given: токен уже ротирован, затем кто-то предъявляет старый
        AuthTokens stolen = registerUser.execute("a@b.c", "password123", "A").tokens();
        AuthTokens legit = refreshSession.execute(stolen.refreshToken());

        // when
        assertThatThrownBy(() -> refreshSession.execute(stolen.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);

        // then: и вор, и легальный клиент выкинуты — вся семья отозвана
        assertThat(refreshTokens.active()).isEmpty();
        assertThatThrownBy(() -> refreshSession.execute(legit.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refresh_shouldReject_expiredToken() {
        AuthTokens tokens = registerUser.execute("a@b.c", "password123", "A").tokens();
        clock.advance(Duration.ofDays(30));

        assertThatThrownBy(() -> refreshSession.execute(tokens.refreshToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refresh_shouldNotOutliveAbsoluteSessionLifetime() {
        AuthTokens tokens = registerUser.execute("a@b.c", "password123", "A").tokens();

        // обновляем каждые 29 дней: токен продлевается, но не дальше 90 дней от входа
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofDays(29));
            tokens = refreshSession.execute(tokens.refreshToken());
        }
        assertThat(tokens.refreshTokenExpiresAt()).isEqualTo(START.plus(Duration.ofDays(90)));

        clock.advance(Duration.ofDays(3)); // 90 дней с входа
        String last = tokens.refreshToken();
        assertThatThrownBy(() -> refreshSession.execute(last)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refresh_shouldReject_unknownOrMissingToken() {
        assertThatThrownBy(() -> refreshSession.execute("unknown")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> refreshSession.execute(null)).isInstanceOf(UnauthorizedException.class);
    }

    // ─── Выход и текущий пользователь ─────────────────────────────────────────

    @Test
    void logout_shouldRevokeSession_andBeIdempotent() {
        AuthTokens tokens = registerUser.execute("a@b.c", "password123", "A").tokens();

        logout.execute(tokens.refreshToken());
        logout.execute(tokens.refreshToken());
        logout.execute(null);

        assertThat(refreshTokens.active()).isEmpty();
        assertThatThrownBy(() -> refreshSession.execute(tokens.refreshToken())).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void getCurrentUser_shouldReturnView_orRejectUnknown() {
        AuthResult result = registerUser.execute("a@b.c", "password123", "A");

        assertThat(getCurrentUser.execute(result.user().id())).isEqualTo(result.user());
        assertThatThrownBy(() -> getCurrentUser.execute(UUID.randomUUID())).isInstanceOf(UnauthorizedException.class);
    }

    // ─── Фейки ────────────────────────────────────────────────────────────────

    /** Выполняет действие сразу — транзакционность проверяется отдельными тестами менеджера. */
    static final class DirectTransactionManager implements TransactionManager {
        @Override public void execute(Runnable action) { action.run(); }
        @Override public <T> T execute(Supplier<T> action) { return action.get(); }
        @Override public <T> T execute(Propagation propagation, Supplier<T> action) { return action.get(); }
        @Override public <T> T execute(Propagation propagation, IsolationLevel isolation, Supplier<T> action) { return action.get(); }
    }

    static final class PrefixHasher implements PasswordHasher {
        @Override public String hash(String raw) { return "hashed:" + raw; }
        @Override public boolean matches(String raw, String hash) { return hash.equals("hashed:" + raw); }
    }

    static final class InMemoryUsers implements UserRepository {
        private final Map<UUID, User> byId = new HashMap<>();
        @Override public void add(User user) { byId.put(user.getId(), user); }
        @Override public Optional<User> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<User> findByEmail(String email) {
            return byId.values().stream().filter(u -> u.getEmail().equals(email)).findFirst();
        }
        @Override public boolean existsByEmail(String email) { return findByEmail(email).isPresent(); }
        User byEmail(String email) { return findByEmail(email).orElseThrow(); }
        int count() { return byId.size(); }
    }

    static final class InMemoryRefreshTokens implements RefreshTokenRepository {
        private final List<RefreshToken> tokens = new ArrayList<>();
        @Override public void add(RefreshToken token) { tokens.add(token); }
        @Override public Optional<RefreshToken> findByHashForUpdate(String hash) {
            return tokens.stream().filter(t -> t.getTokenHash().equals(hash)).findFirst();
        }
        @Override public void revoke(UUID id, Instant at) { replaceIf(t -> t.getId().equals(id), at); }
        @Override public void revokeFamily(UUID familyId, Instant at) { replaceIf(t -> t.getFamilyId().equals(familyId), at); }
        private void replaceIf(java.util.function.Predicate<RefreshToken> match, Instant at) {
            tokens.replaceAll(t -> match.test(t) && !t.isRevoked()
                    ? new RefreshToken(t.getId(), t.getUserId(), t.getFamilyId(), t.getTokenHash(),
                            t.getCreatedAt(), t.getExpiresAt(), t.getSessionExpiresAt(), at)
                    : t);
        }
        List<RefreshToken> active() { return tokens.stream().filter(t -> !t.isRevoked()).toList(); }
        RefreshToken byRaw(String raw) { return findByHashForUpdate(RefreshTokenCodec.hash(raw)).orElseThrow(); }
    }

    static final class CountingLimiter implements LoginAttemptLimiter {
        private final Map<String, Integer> failures = new HashMap<>();
        private final int max;
        CountingLimiter(int max) { this.max = max; }
        @Override public void checkAllowed(String key) {
            if (failures(key) >= max) throw new TooManyRequestsException("too many");
        }
        @Override public void recordFailure(String key) { failures.merge(key, 1, Integer::sum); }
        @Override public void reset(String key) { failures.remove(key); }
        int failures(String key) { return failures.getOrDefault(key, 0); }
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
