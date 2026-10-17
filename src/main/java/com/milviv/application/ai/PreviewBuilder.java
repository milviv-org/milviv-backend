package com.milviv.application.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.application.gamification.RewardCommands;
import com.milviv.application.goal.GoalCommands;
import com.milviv.application.project.ProjectCommands;
import com.milviv.application.task.TaskCommands;
import com.milviv.domain.ai.AiHandle;
import com.milviv.domain.ai.EntityChanges;
import com.milviv.domain.ai.FieldChange;
import com.milviv.domain.ai.OperationReport.EntityType;
import com.milviv.domain.ai.PendingOperation;
import com.milviv.domain.ai.PendingOperation.Op;
import com.milviv.domain.ai.ValidatedPlan;
import com.milviv.domain.exception.AiRequestException;

import static com.milviv.application.ai.contract.PlanDraft.list;

/**
 * Карточка подтверждения (§6.6): изменения и удаления плана → ValidatedPlan.
 *
 * - дескрипторы и ref заменяются на ID — после созданий, поэтому n_* уже существуют;
 * - название берётся из БД, а не из текста AI;
 * - сущность исчезла или изменилась после чтения AI → TARGET_CHANGED: карточка не показывает
 *   операцию, заведомо обречённую на 409.
 *
 * Вызывается в транзакции 3 HandleAiRequest, план уже прошёл AiPlanPolicy.
 */
public class PreviewBuilder {

    private final AiTargets targets;

    public PreviewBuilder(GoalCommands goals, ProjectCommands projects, TaskCommands tasks, RewardCommands rewards) {
        this.targets = new AiTargets(goals, projects, tasks, rewards);
    }

    /** @param refs ref нового проекта → его ID (из исполнения созданий) */
    public ValidatedPlan build(UUID userId, PlanDraft plan, HandleRegistry registry, Map<String, UUID> refs) {
        List<PendingOperation> operations = new ArrayList<>();
        PlanDraft.Update update = plan.update();
        int updates = 0;
        if (update != null) {
            for (PlanDraft.TaskUpdate task : list(update.tasks())) {
                PlanDraft.TaskSet set = task.set();
                operations.add(pending(userId, "u" + (++updates), Op.UPDATE, handle(registry, task.target(), EntityType.TASK),
                        new EntityChanges(set.title(), set.description(), set.difficulty(), set.points(),
                                set.startDate(), set.deadline(), link(set.project(), EntityType.PROJECT, registry, refs),
                                link(set.goal(), EntityType.GOAL, registry, refs), null)));
            }
            for (PlanDraft.ProjectUpdate project : list(update.projects())) {
                PlanDraft.ProjectSet set = project.set();
                operations.add(pending(userId, "u" + (++updates), Op.UPDATE, handle(registry, project.target(), EntityType.PROJECT),
                        new EntityChanges(set.title(), set.description(), null, null, null, set.deadline(), null,
                                link(set.goal(), EntityType.GOAL, registry, refs), null)));
            }
            for (PlanDraft.GoalUpdate goal : list(update.goals())) {
                PlanDraft.GoalSet set = goal.set();
                operations.add(pending(userId, "u" + (++updates), Op.UPDATE, handle(registry, goal.target(), EntityType.GOAL),
                        new EntityChanges(set.title(), set.description(), null, null, null, null, null, null, null)));
            }
            for (PlanDraft.RewardUpdate reward : list(update.rewards())) {
                PlanDraft.RewardSet set = reward.set();
                operations.add(pending(userId, "u" + (++updates), Op.UPDATE, handle(registry, reward.target(), EntityType.REWARD),
                        new EntityChanges(set.title(), set.description(), null, null, null, null, null, null, set.cost())));
            }
        }
        int deletes = 0;
        for (PlanDraft.Delete delete : plan.deletes()) {
            AiHandle handle = registry.find(delete.target())
                    .orElseThrow(() -> new IllegalStateException("Unresolved handle " + delete.target()));
            operations.add(pending(userId, "d" + (++deletes), Op.DELETE, handle, null));
        }
        return ValidatedPlan.of(operations);
    }

    private PendingOperation pending(UUID userId, String itemId, Op op, AiHandle handle, EntityChanges changes) {
        AiTargets.Current current = targets.find(userId, handle.type(), handle.id())
                .orElseThrow(() -> AiRequestException.targetChanged(null));
        if (current.version() != handle.version()) {
            throw AiRequestException.targetChanged(current.title());
        }
        return new PendingOperation(itemId, op, handle.type(), handle.id(), handle.version(), current.title(), changes);
    }

    /** План уже прошёл AiPlanPolicy: неизвестный дескриптор здесь — ошибка программы. */
    private static AiHandle handle(HandleRegistry registry, String handle, EntityType type) {
        return registry.resolve(handle, type)
                .orElseThrow(() -> new IllegalStateException("Unresolved handle " + handle));
    }

    /** Связь из set: дескриптор или n_* → ID; FieldChange(null) — убрать связь. */
    private static FieldChange<UUID> link(FieldChange<String> change, EntityType type, HandleRegistry registry,
                                          Map<String, UUID> refs) {
        if (change == null) {
            return null;
        }
        String value = change.value();
        if (value == null) {
            return new FieldChange<>(null);
        }
        if (value.startsWith("n_")) {
            UUID id = refs.get(value);
            if (id == null) {
                throw new IllegalStateException("Unresolved ref " + value);
            }
            return new FieldChange<>(id);
        }
        return new FieldChange<>(handle(registry, value, type).id());
    }
}
