package com.milviv.infrastructure.config;

import com.milviv.application.reflection.ReflectionPolicy;
import com.milviv.infrastructure.container.Inject;

/** Настройки рефлексии. Источники и приоритет — как в AppConfig (ConfigLoader). */
public class ReflectionConfig {

    private final int maxOwnQuestions;

    @Inject
    public ReflectionConfig(ConfigLoader config) {
        this.maxOwnQuestions = config.getInt("REFLECTION_MAX_OWN_QUESTIONS", "reflection.max.own.questions", 100);
        if (maxOwnQuestions < 0) {
            throw new IllegalStateException("REFLECTION_MAX_OWN_QUESTIONS must be >= 0");
        }
    }

    public ReflectionPolicy toPolicy() {
        return new ReflectionPolicy(maxOwnQuestions);
    }

    @Override
    public String toString() {
        return "ReflectionConfig{maxOwnQuestions=" + maxOwnQuestions + "}";
    }
}
