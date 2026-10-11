package com.milviv.unit.application.ai;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.milviv.application.ai.AiLimits;
import com.milviv.application.ai.AiTurnValidator;
import com.milviv.application.ai.contract.AiTurn;
import com.milviv.domain.ai.RefusalReason;

class AiTurnValidatorTest {

    /** Дефолты §8 с заданными пределами вызовов, таймаута и длин ответа и вопроса. */
    static AiLimits limits(int maxCalls, Duration requestTimeout, int answerMax, int clarificationMax) {
        return new AiLimits(
                new AiLimits.Request(2000, 20, 3, maxCalls, requestTimeout, Duration.ofHours(24), 200, Duration.ofMinutes(60)),
                new AiLimits.Data(2, 3, 100, 1000, 16_000),
                new AiLimits.Plan(40, 20, 10, 10, 10, 30, 3, 100, 1000, 500, answerMax, clarificationMax, 200, 2, 100_000));
    }

    static AiLimits limits(int maxCalls, Duration requestTimeout) {
        return limits(maxCalls, requestTimeout, 2000, 300);
    }

    /** Длины ответа и вопроса уменьшены, чтобы проверять предел. */
    private final AiTurnValidator validator = new AiTurnValidator(limits(4, Duration.ofSeconds(90), 20, 10));

    @Test
    void validTurns_haveNoViolations() {
        assertThat(validator.validate(new AiTurn.Answer("Готово"))).isEmpty();
        assertThat(validator.validate(new AiTurn.ClarificationNeeded("Какой?"))).isEmpty();
        assertThat(validator.validate(new AiTurn.Refused(RefusalReason.OFF_TOPIC))).isEmpty();
    }

    @Test
    void emptyOrTooLong_areViolations() {
        assertThat(validator.validate(new AiTurn.Answer(" "))).containsExactly("answer: REQUIRED");
        assertThat(validator.validate(new AiTurn.Answer("x".repeat(21)))).containsExactly("answer: MAX_LENGTH(20)");
        assertThat(validator.validate(new AiTurn.ClarificationNeeded(null))).containsExactly("clarificationQuestion: REQUIRED");
        assertThat(validator.validate(new AiTurn.ClarificationNeeded("x".repeat(11))))
                .containsExactly("clarificationQuestion: MAX_LENGTH(10)");
        assertThat(validator.validate(new AiTurn.Refused(null))).containsExactly("refusalReason: REQUIRED");
    }

    @Test
    void normalize_trimsAndDropsControlCharacters_keepingNewlines() {
        AiTurn turn = validator.normalize(new AiTurn.Answer("  Строка 1\nСтрока\u0007 2\u0000  "));

        assertThat(((AiTurn.Answer) turn).answer()).isEqualTo("Строка 1\nСтрока 2");
    }
}
