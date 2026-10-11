package com.milviv.domain.task;

/** Уровни сложности и баллы по умолчанию (пользовательская документация, gamification). */
public enum Difficulty {

    NOVICE(2),
    APPRENTICE(3),
    ADEPT(5),
    EXPERT(8),
    MASTER(10),
    LEGENDARY(20);

    private final int defaultPoints;

    Difficulty(int defaultPoints) {
        this.defaultPoints = defaultPoints;
    }

    public int defaultPoints() {
        return defaultPoints;
    }
}
