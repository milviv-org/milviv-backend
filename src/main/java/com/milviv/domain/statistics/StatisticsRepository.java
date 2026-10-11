package com.milviv.domain.statistics;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.milviv.domain.project.ProjectStatus;

/** Агрегирующие запросы по проектам и целям. Каждый метод фильтрует по userId (AD-14). */
public interface StatisticsRepository {

    record ProjectProgress(UUID projectId, String title, ProjectStatus status, LocalDate deadline, WorkProgress progress) {}

    /** @param projects проектов цели */
    record GoalProgress(UUID goalId, String title, int projects, WorkProgress progress) {}

    /**
     * Проекты, новые первыми. status = null — все.
     *
     * @param today для подсчёта просроченных
     */
    List<ProjectProgress> projectProgress(UUID userId, ProjectStatus status, LocalDate today, int limit, int offset);

    /**
     * Цели, новые первыми. Задачи цели — привязанные к ней напрямую и через её проекты,
     * каждая считается один раз.
     */
    List<GoalProgress> goalProgress(UUID userId, LocalDate today, int limit, int offset);
}
