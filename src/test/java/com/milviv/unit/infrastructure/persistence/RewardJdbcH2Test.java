package com.milviv.unit.infrastructure.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.milviv.application.auth.PasswordHasher;
import com.milviv.application.gamification.HiddenRewardsAccess;
import com.milviv.application.gamification.PointsEntryView;
import com.milviv.application.gamification.PointsQueries;
import com.milviv.application.gamification.RewardCommands;
import com.milviv.application.gamification.RewardView;
import com.milviv.application.gamification.SetHiddenRewardsPassword;
import com.milviv.application.transaction.TransactionManager;
import com.milviv.domain.exception.ConflictException;
import com.milviv.domain.exception.ForbiddenException;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.exception.TooManyRequestsException;
import com.milviv.domain.gamification.PointsEntry;
import com.milviv.domain.gamification.RewardDetails;
import com.milviv.infrastructure.persistence.connection.TransactionalConnectionProvider;
import com.milviv.infrastructure.persistence.jdbc.HiddenRewardsPasswordJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.PointsHistoryJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.RewardJdbcRepository;
import com.milviv.infrastructure.persistence.jdbc.UserJdbcRepository;
import com.milviv.infrastructure.persistence.transaction.JdbcTransactionManager;
import com.milviv.infrastructure.persistence.transaction.RetryableTransactionManager;
import com.milviv.infrastructure.security.InMemoryLoginAttemptLimiter;

/** Награды на H2: видимость скрытых, пароль, получение без ухода баланса в минус, история баллов. */
class RewardJdbcH2Test {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final String ACCOUNT_PASSWORD = "account-pass";
    private static final String HIDDEN = "secret";

    /** Быстрая замена BCrypt: проверяется логика, а не стойкость хеша. */
    private static final PasswordHasher HASHER = new PasswordHasher() {
        @Override public String hash(String raw) { return "h:" + raw; }
        @Override public boolean matches(String raw, String hash) { return hash.equals("h:" + raw); }
    };

    private Connection keepAlive;
    private RewardCommands rewards;
    private PointsQueries points;
    private SetHiddenRewardsPassword setPassword;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:rewards" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        keepAlive = dataSource.getConnection();
        try (Statement st = keepAlive.createStatement()) {
            H2WorkSchema.create(st);
            for (UUID user : List.of(alice, bob)) {
                st.execute("INSERT INTO users (id, email, password_hash, name, created_at, updated_at) VALUES ('" + user
                        + "', '" + user + "@test.dev', 'h:" + ACCOUNT_PASSWORD + "', 'U', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }

        TransactionalConnectionProvider connections = new TransactionalConnectionProvider();
        TransactionManager tx = new RetryableTransactionManager(new JdbcTransactionManager(dataSource), 3, 1, 1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var limiter = new InMemoryLoginAttemptLimiter(3, Duration.ofMinutes(15), clock);
        var passwords = new HiddenRewardsPasswordJdbcRepository(connections);
        var history = new PointsHistoryJdbcRepository(connections);
        HiddenRewardsAccess access = new HiddenRewardsAccess(passwords, HASHER, limiter, tx);

        rewards = new RewardCommands(new RewardJdbcRepository(connections), history, access, tx, clock);
        points = new PointsQueries(history, access, tx);
        setPassword = new SetHiddenRewardsPassword(new UserJdbcRepository(connections), passwords, HASHER, limiter, tx);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("SHUTDOWN");
        }
        keepAlive.close();
    }

    private static RewardDetails reward(String title, int cost, boolean hidden) {
        return new RewardDetails(title, null, cost, hidden);
    }

    private void earn(UUID user, int amount) throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute("INSERT INTO points_history (user_id, kind, amount, reason, created_at) VALUES ('"
                    + user + "', 'TASK_COMPLETED', " + amount + ", 'seed', CURRENT_TIMESTAMP)");
        }
    }

    private RewardView hiddenReward(String title, int cost) {
        setPassword.execute(alice, ACCOUNT_PASSWORD, HIDDEN);
        return rewards.create(alice, reward(title, cost, true), HIDDEN);
    }

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    @Test
    void reward_crud_andIsolation() {
        RewardView game = rewards.create(alice, reward(" Новая игра ", 5000, false), null);
        assertThat(game.title()).isEqualTo("Новая игра");
        assertThat(rewards.get(alice, game.id(), null)).isEqualTo(game);

        RewardView cheaper = rewards.update(alice, game.id(), reward("Новая игра", 4000, false), game.version(), null);
        assertThat(cheaper.cost()).isEqualTo(4000);
        assertThatThrownBy(() -> rewards.update(alice, game.id(), reward("x", 1, false), game.version(), null))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> rewards.get(bob, game.id(), null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rewards.redeem(bob, game.id(), null)).isInstanceOf(NotFoundException.class);
        assertThat(rewards.list(bob, null, 50, 0)).isEmpty();

        rewards.delete(alice, game.id(), null);
        assertThat(rewards.list(alice, null, 50, 0)).isEmpty();
    }

    // ─── Скрытые награды ──────────────────────────────────────────────────────

    @Test
    void hiddenReward_isInvisibleWithoutPassword() {
        RewardView visible = rewards.create(alice, reward("Кино", 10, false), null);
        RewardView hidden = hiddenReward("Тайное", 20);

        assertThat(rewards.list(alice, null, 50, 0)).extracting(RewardView::id).containsExactly(visible.id());
        assertThatThrownBy(() -> rewards.get(alice, hidden.id(), null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rewards.update(alice, hidden.id(), reward("x", 1, false), hidden.version(), null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rewards.delete(alice, hidden.id(), null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rewards.redeem(alice, hidden.id(), null)).isInstanceOf(NotFoundException.class);

        assertThat(rewards.list(alice, HIDDEN, 50, 0)).extracting(RewardView::id).containsExactly(visible.id(), hidden.id());
        assertThat(rewards.get(alice, hidden.id(), HIDDEN).hidden()).isTrue();
    }

    @Test
    void hiding_requiresPassword() {
        assertThatThrownBy(() -> rewards.create(alice, reward("Тайное", 1, true), null))
                .isInstanceOf(ForbiddenException.class);
        // пароль ещё не задан
        assertThatThrownBy(() -> rewards.create(alice, reward("Тайное", 1, true), HIDDEN))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("не задан");

        RewardView visible = rewards.create(alice, reward("Кино", 10, false), null);
        setPassword.execute(alice, ACCOUNT_PASSWORD, HIDDEN);
        assertThatThrownBy(() -> rewards.update(alice, visible.id(), reward("Кино", 10, true), visible.version(), null))
                .isInstanceOf(ForbiddenException.class);
        assertThat(rewards.update(alice, visible.id(), reward("Кино", 10, true), visible.version(), HIDDEN).hidden()).isTrue();
    }

    @Test
    void wrongHiddenPassword_isForbidden_andRateLimited() {
        hiddenReward("Тайное", 20);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> rewards.list(alice, "wrong", 50, 0)).isInstanceOf(ForbiddenException.class);
        }
        // попытки исчерпаны — даже верный пароль не проверяется
        assertThatThrownBy(() -> rewards.list(alice, HIDDEN, 50, 0)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void setPassword_requiresAccountPassword_andCanChangeIt() {
        assertThat(setPassword.isSet(alice)).isFalse();
        assertThatThrownBy(() -> setPassword.execute(alice, "wrong", HIDDEN)).isInstanceOf(ForbiddenException.class);

        RewardView hidden = hiddenReward("Тайное", 20);
        assertThat(setPassword.isSet(alice)).isTrue();
        assertThat(setPassword.isSet(bob)).isFalse();

        setPassword.execute(alice, ACCOUNT_PASSWORD, "new-secret");
        assertThatThrownBy(() -> rewards.get(alice, hidden.id(), HIDDEN)).isInstanceOf(ForbiddenException.class);
        assertThat(rewards.get(alice, hidden.id(), "new-secret").id()).isEqualTo(hidden.id());
    }

    // ─── Получение награды ────────────────────────────────────────────────────

    @Test
    void redeem_shouldSpendPoints_andNeverGoNegative() throws SQLException {
        RewardView cinema = rewards.create(alice, reward("Кино", 10, false), null);
        earn(alice, 15);

        RewardCommands.RedeemResult result = rewards.redeem(alice, cinema.id(), null);
        assertThat(result.pointsSpent()).isEqualTo(10);
        assertThat(result.balance()).isEqualTo(5);
        assertThat(points.balance(alice).balance()).isEqualTo(5);

        assertThatThrownBy(() -> rewards.redeem(alice, cinema.id(), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Недостаточно баллов");
        assertThat(points.balance(alice).balance()).isEqualTo(5);

        // награду можно получать повторно; баланс может стать ровно нулём
        earn(alice, 5);
        assertThat(rewards.redeem(alice, cinema.id(), null).balance()).isZero();
    }

    @Test
    void concurrentRedeems_shouldNotOverspend() throws Exception {
        RewardView cinema = rewards.create(alice, reward("Кино", 10, false), null);
        earn(alice, 10);

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    rewards.redeem(alice, cinema.id(), null);
                    return true;
                } catch (ConflictException ex) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            succeeded += result.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(points.balance(alice).balance()).isZero();
    }

    // ─── История баллов ───────────────────────────────────────────────────────

    @Test
    void history_shouldHideHiddenRewardsWithoutPassword() throws SQLException {
        RewardView hidden = hiddenReward("Тайное", 20);
        RewardView visible = rewards.create(alice, reward("Кино", 10, false), null);
        earn(alice, 100);
        rewards.redeem(alice, hidden.id(), HIDDEN);
        rewards.redeem(alice, visible.id(), null);

        List<PointsEntryView> masked = points.history(alice, null, 50, 0);
        assertThat(masked).extracting(PointsEntryView::amount).containsExactlyInAnyOrder(100, -20, -10);
        assertThat(masked).extracting(PointsEntryView::rewardId).doesNotContain(hidden.id()).contains(visible.id());
        assertThat(masked).extracting(PointsEntryView::reason).noneMatch(reason -> reason.contains("Тайное"));

        List<PointsEntryView> unlocked = points.history(alice, HIDDEN, 50, 0);
        assertThat(unlocked).extracting(PointsEntryView::rewardId).contains(hidden.id());
        // название скрытой награды не сохраняется в истории вовсе
        assertThat(unlocked).extracting(PointsEntryView::reason).contains(PointsEntry.HIDDEN_REWARD_REASON, "Reward redeemed: Кино");
    }

    @Test
    void deleteReward_shouldKeepHistory() throws SQLException {
        RewardView cinema = rewards.create(alice, reward("Кино", 10, false), null);
        earn(alice, 10);
        rewards.redeem(alice, cinema.id(), null);

        rewards.delete(alice, cinema.id(), null);

        assertThat(points.balance(alice).balance()).isZero();
        assertThat(count("SELECT COUNT(*) FROM points_history WHERE kind = 'REWARD_REDEEMED' AND reward_id IS NULL")).isEqualTo(1);
    }

    private int count(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
