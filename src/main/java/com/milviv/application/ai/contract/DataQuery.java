package com.milviv.application.ai.contract;

import java.time.LocalDate;

import com.milviv.domain.project.ProjectStatus;
import com.milviv.domain.task.TaskStatus;

/**
 * Запрос данных из закрытого каталога v1 (§6.3). Параметры типизированы: неприменимого
 * к запросу параметра у него просто нет. Ссылки — только дескрипторы, выданные в этом AI-запросе.
 * Параметра «пользователь» нет: user_id всегда из сессии (T-5).
 */
public sealed interface DataQuery {

    /** Имя запроса в контракте: LIST_GOALS, LIST_TASKS… */
    String query();

    /** Период дат включительно. */
    record Period(LocalDate from, LocalDate to) {}

    record ListGoals() implements DataQuery {
        @Override public String query() { return "LIST_GOALS"; }
    }

    record ListProjects(String goal, ProjectStatus status) implements DataQuery {
        @Override public String query() { return "LIST_PROJECTS"; }
    }

    /** @param unassigned true — только задачи без проекта и без цели */
    record ListTasks(String project, String goal, Boolean unassigned, TaskStatus status, Period period) implements DataQuery {
        @Override public String query() { return "LIST_TASKS"; }
    }

    /** Только нескрытые награды (G-5). */
    record ListRewards() implements DataQuery {
        @Override public String query() { return "LIST_REWARDS"; }
    }

    /** @param period обязателен, не длиннее 31 дня */
    record ListPlans(Period period) implements DataQuery {
        @Override public String query() { return "LIST_PLANS"; }
    }

    record ListReflections(String task, Period period) implements DataQuery {
        @Override public String query() { return "LIST_REFLECTIONS"; }
    }
}
