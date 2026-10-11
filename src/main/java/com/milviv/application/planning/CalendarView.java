package com.milviv.application.planning;

import java.time.LocalDate;
import java.util.List;

import com.milviv.application.project.ProjectView;
import com.milviv.application.task.TaskView;

/** Календарь за период: по записи на каждый день, включая пустые. */
public record CalendarView(LocalDate from, LocalDate to, List<Day> days) {

    /**
     * @param plans            планы этого дня по времени начала: разовые и повторяющиеся
     * @param taskDeadlines    активные задачи со сроком в этот день
     * @param completedTasks   задачи, выполненные в этот день
     * @param projectDeadlines активные проекты со сроком в этот день
     */
    public record Day(
            LocalDate date,
            List<PlanView> plans,
            List<TaskView> taskDeadlines,
            List<TaskView> completedTasks,
            List<ProjectView> projectDeadlines
    ) {}
}
