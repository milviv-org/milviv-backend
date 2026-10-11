package com.milviv.infrastructure.config;

import com.milviv.application.statistics.StatisticsPolicy;
import com.milviv.infrastructure.container.Inject;

/** Настройки статистики. Источники и приоритет — как в AppConfig (ConfigLoader). */
public class StatisticsConfig {

    private final int maxDays;

    @Inject
    public StatisticsConfig(ConfigLoader config) {
        this.maxDays = config.getInt("STATISTICS_MAX_DAYS", "statistics.max.days", 366);
        if (maxDays < 1 || maxDays > 3660) {
            throw new IllegalStateException("STATISTICS_MAX_DAYS must be between 1 and 3660");
        }
    }

    public StatisticsPolicy toPolicy() {
        return new StatisticsPolicy(maxDays);
    }

    @Override
    public String toString() {
        return "StatisticsConfig{maxDays=" + maxDays + "}";
    }
}
