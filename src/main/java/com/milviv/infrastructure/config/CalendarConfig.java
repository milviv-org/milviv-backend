package com.milviv.infrastructure.config;

import com.milviv.application.planning.CalendarPolicy;
import com.milviv.infrastructure.container.Inject;

/** Настройки календаря. Источники и приоритет — как в AppConfig (ConfigLoader). */
public class CalendarConfig {

    private final int maxDays;

    @Inject
    public CalendarConfig(ConfigLoader config) {
        this.maxDays = config.getInt("CALENDAR_MAX_DAYS", "calendar.max.days", 62);
        if (maxDays < 1 || maxDays > 366) {
            throw new IllegalStateException("CALENDAR_MAX_DAYS must be between 1 and 366");
        }
    }

    public CalendarPolicy toPolicy() {
        return new CalendarPolicy(maxDays);
    }

    @Override
    public String toString() {
        return "CalendarConfig{maxDays=" + maxDays + "}";
    }
}
