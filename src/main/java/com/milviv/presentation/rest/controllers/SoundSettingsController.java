package com.milviv.presentation.rest.controllers;

import java.util.List;

import com.milviv.application.sound.SoundSettings;
import com.milviv.application.sound.SoundSettings.SoundSettingView;
import com.milviv.domain.exception.NotFoundException;
import com.milviv.domain.sound.SoundEvent;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Put;
import com.milviv.shared.validation.annotation.annotations.Pattern;
import com.milviv.shared.validation.annotation.annotations.Required;

/** Звуки событий текущего пользователя. Все маршруты требуют входа. */
@Controller("/settings/sounds")
public class SoundSettingsController {

    /** Ключ звука из каталога клиента: латиница в нижнем регистре, цифры, «_», «-», «.», до 100 символов. */
    record SoundRequest(
            @Required @Pattern(pattern = "^[a-z0-9][a-z0-9_.-]{0,99}$",
                    message = "Ключ звука: a-z, 0-9, «_», «-», «.», до 100 символов") String sound
    ) {}

    record SoundSettingsResponse(List<SoundSettingView> items) {}

    private final SoundSettings soundSettings;

    public SoundSettingsController(SoundSettings soundSettings) {
        this.soundSettings = soundSettings;
    }

    @Get("")
    public void get(HttpContext ctx) throws Exception {
        ctx.json(200, new SoundSettingsResponse(soundSettings.get(ctx.userId())));
    }

    @Put("/{event}")
    public void set(HttpContext ctx) throws Exception {
        SoundRequest body = ctx.body(SoundRequest.class);
        ctx.json(200, new SoundSettingsResponse(soundSettings.set(ctx.userId(), event(ctx), body.sound())));
    }

    @Delete("/{event}")
    public void reset(HttpContext ctx) throws Exception {
        ctx.json(200, new SoundSettingsResponse(soundSettings.reset(ctx.userId(), event(ctx))));
    }

    private static SoundEvent event(HttpContext ctx) {
        try {
            return SoundEvent.valueOf(ctx.pathParam("event"));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new NotFoundException("Событие не найдено");
        }
    }
}
