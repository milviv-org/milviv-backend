package com.milviv.domain.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Хранилище планов. Каждый метод фильтрует по userId (AD-14). */
public interface PlanRepository {

    void add(Plan plan);

    Optional<Plan> findById(UUID userId, UUID planId);

    /**
     * С блокировкой строки до конца транзакции. У планов нет версии: блокировка нужна,
     * чтобы два параллельных изменения не записали один перенос дважды.
     */
    Optional<Plan> findByIdForUpdate(UUID userId, UUID planId);

    /** Планы пользователя: разовые по дате, затем повторяющиеся. taskId = null — все. */
    List<Plan> findByUser(UUID userId, UUID taskId, int limit, int offset);

    /** Разовые планы с датой в [from, to] и все повторяющиеся. */
    List<Plan> findForPeriod(UUID userId, LocalDate from, LocalDate to);

    /** Самая поздняя дата разовых планов задачи — плановая дата для факта выполнения. */
    Optional<LocalDate> findLatestPlanDateForTask(UUID userId, UUID taskId);

    void update(Plan plan);

    /** @return true, если план был и удалён */
    boolean delete(UUID userId, UUID planId);
}
