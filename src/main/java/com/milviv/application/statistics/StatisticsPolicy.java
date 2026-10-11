package com.milviv.application.statistics;

/** @param maxDays самый длинный период сводки за один запрос, дней */
public record StatisticsPolicy(int maxDays) {}
