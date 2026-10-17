package com.milviv.application.ai;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.milviv.application.ai.AiPlanPolicy.Verdict;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.application.ai.contract.PlanDraft;
import com.milviv.domain.ai.FieldChange;

import static com.milviv.application.ai.contract.PlanDraft.list;

/**
 * Проверка хода AI (§6.2, §8). Ход недоверенный: проверяется всё, что придёт пользователю.
 * Замечания уходят AI в повторный вызов — коротко, в виде «поле: код». План проверяет AiPlanPolicy.
 */
public class AiTurnValidator {

    private final AiLimits limits;
    private final AiPlanPolicy planPolicy;

    public AiTurnValidator(AiLimits limits, AiPlanPolicy planPolicy) {
        this.limits = limits;
        this.planPolicy = planPolicy;
    }

    /**
     * Единственная мягкая нормализация (§6.2): обрезка пробелов и удаление управляющих
     * символов, кроме перевода строки, — во всех текстах хода, включая план.
     */
    public AiTurn normalize(AiTurn turn) {
        return switch (turn) {
            case AiTurn.Answer a -> new AiTurn.Answer(clean(a.answer()));
            case AiTurn.ClarificationNeeded c -> new AiTurn.ClarificationNeeded(clean(c.clarificationQuestion()));
            case AiTurn.Refused r -> r;
            case AiTurn.Proposal p -> new AiTurn.Proposal(clean(p.summary()), clean(p.answer()), clean(p.plan()));
            case AiTurn.DataRequest d -> new AiTurn.DataRequest(clean(d.dataRequestReason()), d.dataRequests());
        };
    }

    /**
     * @param registry дескрипторы, выданные в этом запросе, — для existing в плане
     * @return пусто — ход годится
     */
    public Verdict validate(AiTurn turn, LocalDate today, HandleRegistry registry) {
        List<String> violations = new ArrayList<>();
        switch (turn) {
            case AiTurn.Answer a -> text(violations, "answer", a.answer(), limits.plan().answerMaxLength(), true);
            case AiTurn.ClarificationNeeded c ->
                    text(violations, "clarificationQuestion", c.clarificationQuestion(), limits.plan().clarificationMaxLength(), true);
            case AiTurn.Refused r -> {
                if (r.refusalReason() == null) {
                    violations.add("refusalReason: REQUIRED");
                }
            }
            case AiTurn.Proposal p -> {
                text(violations, "summary", p.summary(), limits.plan().summaryMaxLength(), true);
                text(violations, "answer", p.answer(), limits.plan().answerMaxLength(), false);
                Verdict plan = planPolicy.check(p.plan(), today, registry);
                if (plan.tooLarge()) {
                    return plan;
                }
                violations.addAll(plan.violations());
            }
            case AiTurn.DataRequest d -> {
                text(violations, "dataRequestReason", d.dataRequestReason(), limits.plan().dataRequestReasonMaxLength(), true);
                int count = d.dataRequests() == null ? 0 : d.dataRequests().size();
                if (count < 1 || count > limits.data().maxRequestsPerRound()) {
                    violations.add("dataRequests: COUNT(1.." + limits.data().maxRequestsPerRound() + ")");
                } else if (d.dataRequests().stream().anyMatch(Objects::isNull)) {
                    violations.add("dataRequests: NULL_ITEM");
                }
            }
        }
        return new Verdict(violations, false);
    }

    private static void text(List<String> violations, String field, String value, int maxLength, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                violations.add(field + ": REQUIRED");
            }
        } else if (value.length() > maxLength) {
            violations.add(field + ": MAX_LENGTH(" + maxLength + ")");
        }
    }

    // ─── Нормализация плана ───────────────────────────────────────────────────

    private static PlanDraft clean(PlanDraft plan) {
        if (plan == null) {
            return null;
        }
        List<PlanDraft.Delete> deletes = plan.delete() == null ? null
                : plan.delete().stream().map(d -> d == null ? null : new PlanDraft.Delete(clean(d.target()))).toList();
        return new PlanDraft(clean(plan.create()), clean(plan.update()), deletes);
    }

    private static PlanDraft.Create clean(PlanDraft.Create c) {
        if (c == null) {
            return null;
        }
        PlanDraft.GoalNode goal = c.goal() == null ? null : new PlanDraft.GoalNode(clean(c.goal().existing()),
                clean(c.goal().title()), clean(c.goal().description()), projects(c.goal().projects()), tasks(c.goal().tasks()));
        List<PlanDraft.RewardNode> rewards = list(c.rewards()).stream()
                .map(r -> new PlanDraft.RewardNode(clean(r.title()), clean(r.description()), r.cost()))
                .toList();
        return new PlanDraft.Create(goal, projects(c.projects()), tasks(c.tasks()), rewards);
    }

    /** null внутри списков оставляется как есть — его отклонит AiPlanPolicy. */
    private static PlanDraft.Update clean(PlanDraft.Update u) {
        if (u == null) {
            return null;
        }
        return new PlanDraft.Update(
                list(u.tasks()).stream().map(t -> t == null || t.set() == null ? t : new PlanDraft.TaskUpdate(clean(t.target()),
                        new PlanDraft.TaskSet(cleanChange(t.set().title()), cleanChange(t.set().description()),
                                t.set().difficulty(), t.set().points(), t.set().startDate(), t.set().deadline(),
                                cleanChange(t.set().project()), cleanChange(t.set().goal())))).toList(),
                list(u.projects()).stream().map(p -> p == null || p.set() == null ? p : new PlanDraft.ProjectUpdate(clean(p.target()),
                        new PlanDraft.ProjectSet(cleanChange(p.set().title()), cleanChange(p.set().description()),
                                p.set().deadline(), cleanChange(p.set().goal())))).toList(),
                list(u.goals()).stream().map(g -> g == null || g.set() == null ? g : new PlanDraft.GoalUpdate(clean(g.target()),
                        new PlanDraft.GoalSet(cleanChange(g.set().title()), cleanChange(g.set().description())))).toList(),
                list(u.rewards()).stream().map(r -> r == null || r.set() == null ? r : new PlanDraft.RewardUpdate(clean(r.target()),
                        new PlanDraft.RewardSet(cleanChange(r.set().title()), cleanChange(r.set().description()),
                                r.set().cost()))).toList());
    }

    private static FieldChange<String> cleanChange(FieldChange<String> change) {
        return change == null ? null : new FieldChange<>(clean(change.value()));
    }

    private static List<PlanDraft.ProjectNode> projects(List<PlanDraft.ProjectNode> projects) {
        return list(projects).stream()
                .map(p -> new PlanDraft.ProjectNode(clean(p.ref()), clean(p.existing()), clean(p.title()),
                        clean(p.description()), p.deadline(), tasks(p.tasks())))
                .toList();
    }

    private static List<PlanDraft.TaskNode> tasks(List<PlanDraft.TaskNode> tasks) {
        return list(tasks).stream()
                .map(t -> new PlanDraft.TaskNode(clean(t.title()), clean(t.description()), t.difficulty(), t.points(),
                        t.startDate(), t.deadline()))
                .toList();
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
    }
}
