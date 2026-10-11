package com.milviv.presentation.rest.controllers;

import java.util.List;
import java.util.UUID;

import com.milviv.application.gamification.RewardCommands;
import com.milviv.application.gamification.RewardView;
import com.milviv.application.gamification.SetHiddenRewardsPassword;
import com.milviv.domain.gamification.RewardDetails;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.infrastructure.http.annotations.Put;
import com.milviv.shared.validation.annotation.annotations.Max;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.Min;
import com.milviv.shared.validation.annotation.annotations.MinLength;
import com.milviv.shared.validation.annotation.annotations.Required;

/**
 * Награды текущего пользователя. Все маршруты требуют входа.
 * Скрытые награды доступны только с заголовком HIDDEN_PASSWORD_HEADER.
 */
@Controller("/rewards")
public class RewardController {

    static final String HIDDEN_PASSWORD_HEADER = "X-Hidden-Rewards-Password";

    record CreateRewardRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description,
            @Required @Min(num = 1) @Max(num = 1_000_000) Integer cost,
            Boolean hidden
    ) {
        RewardDetails toDetails() {
            return new RewardDetails(title, description, cost, Boolean.TRUE.equals(hidden));
        }
    }

    /** Полная замена полей; hidden не передан — награда видимая. version — версия, которую видел клиент. */
    record UpdateRewardRequest(
            @Required @MaxLength(length = 255) String title,
            @MaxLength(length = 5000) String description,
            @Required @Min(num = 1) @Max(num = 1_000_000) Integer cost,
            Boolean hidden,
            @Required Long version
    ) {
        RewardDetails toDetails() {
            return new RewardDetails(title, description, cost, Boolean.TRUE.equals(hidden));
        }
    }

    record HiddenPasswordRequest(
            @Required @MaxLength(length = 64) String accountPassword,
            @Required @MinLength(length = 4) @MaxLength(length = 64) String newPassword
    ) {}

    record HiddenPasswordStatus(boolean set) {}

    record RewardListResponse(List<RewardView> items) {}

    private final RewardCommands rewardCommands;
    private final SetHiddenRewardsPassword setHiddenPassword;

    public RewardController(RewardCommands rewardCommands, SetHiddenRewardsPassword setHiddenPassword) {
        this.rewardCommands = rewardCommands;
        this.setHiddenPassword = setHiddenPassword;
    }

    @Post("")
    public void create(HttpContext ctx) throws Exception {
        CreateRewardRequest body = ctx.body(CreateRewardRequest.class);
        ctx.json(201, rewardCommands.create(ctx.userId(), body.toDetails(), hiddenPassword(ctx)));
    }

    @Get("")
    public void list(HttpContext ctx) throws Exception {
        int limit = RequestParams.intQuery(ctx, "limit", RequestParams.DEFAULT_PAGE_SIZE);
        int offset = RequestParams.intQuery(ctx, "offset", 0);
        ctx.json(200, new RewardListResponse(rewardCommands.list(ctx.userId(), hiddenPassword(ctx), limit, offset)));
    }

    @Get("/{id}")
    public void get(HttpContext ctx) throws Exception {
        ctx.json(200, rewardCommands.get(ctx.userId(), rewardId(ctx), hiddenPassword(ctx)));
    }

    @Put("/{id}")
    public void update(HttpContext ctx) throws Exception {
        UpdateRewardRequest body = ctx.body(UpdateRewardRequest.class);
        ctx.json(200, rewardCommands.update(ctx.userId(), rewardId(ctx), body.toDetails(), body.version(), hiddenPassword(ctx)));
    }

    @Delete("/{id}")
    public void delete(HttpContext ctx) {
        rewardCommands.delete(ctx.userId(), rewardId(ctx), hiddenPassword(ctx));
        ctx.status(204);
    }

    @Post("/{id}/redeem")
    public void redeem(HttpContext ctx) throws Exception {
        ctx.json(200, rewardCommands.redeem(ctx.userId(), rewardId(ctx), hiddenPassword(ctx)));
    }

    // Три сегмента пути — не пересекается с /rewards/{id}

    @Get("/hidden/password")
    public void hiddenPasswordStatus(HttpContext ctx) throws Exception {
        ctx.json(200, new HiddenPasswordStatus(setHiddenPassword.isSet(ctx.userId())));
    }

    @Put("/hidden/password")
    public void setHiddenPassword(HttpContext ctx) throws Exception {
        HiddenPasswordRequest body = ctx.body(HiddenPasswordRequest.class);
        setHiddenPassword.execute(ctx.userId(), body.accountPassword(), body.newPassword());
        ctx.status(204);
    }

    private static String hiddenPassword(HttpContext ctx) {
        return ctx.header(HIDDEN_PASSWORD_HEADER);
    }

    private static UUID rewardId(HttpContext ctx) {
        return RequestParams.pathId(ctx, "Награда не найдена");
    }
}
