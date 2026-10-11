package com.milviv.application.ai;

import java.util.ArrayList;
import java.util.List;

import com.milviv.application.ai.contract.AiTurn;

/**
 * Проверка хода AI (§6.2, §8). Ход недоверенный: проверяется всё, что придёт пользователю.
 * Замечания уходят AI в повторный вызов — коротко, в виде «поле: код».
 */
public class AiTurnValidator {

    private final AiLimits limits;

    public AiTurnValidator(AiLimits limits) {
        this.limits = limits;
    }

    /**
     * Единственная мягкая нормализация (§6.2): обрезка пробелов и удаление управляющих
     * символов, кроме перевода строки.
     */
    public AiTurn normalize(AiTurn turn) {
        return switch (turn) {
            case AiTurn.Answer a -> new AiTurn.Answer(clean(a.answer()));
            case AiTurn.ClarificationNeeded c -> new AiTurn.ClarificationNeeded(clean(c.clarificationQuestion()));
            case AiTurn.Refused r -> r;
        };
    }

    /** @return пусто — ход годится */
    public List<String> validate(AiTurn turn) {
        List<String> violations = new ArrayList<>();
        switch (turn) {
            case AiTurn.Answer a -> text(violations, "answer", a.answer(), limits.plan().answerMaxLength());
            case AiTurn.ClarificationNeeded c ->
                    text(violations, "clarificationQuestion", c.clarificationQuestion(), limits.plan().clarificationMaxLength());
            case AiTurn.Refused r -> {
                if (r.refusalReason() == null) {
                    violations.add("refusalReason: REQUIRED");
                }
            }
        }
        return violations;
    }

    private static void text(List<String> violations, String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            violations.add(field + ": REQUIRED");
        } else if (value.length() > maxLength) {
            violations.add(field + ": MAX_LENGTH(" + maxLength + ")");
        }
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
    }
}
