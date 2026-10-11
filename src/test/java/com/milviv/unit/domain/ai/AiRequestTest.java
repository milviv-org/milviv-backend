package com.milviv.unit.domain.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.milviv.domain.ai.AiRequest;
import com.milviv.domain.ai.AiRequestStatus;
import com.milviv.domain.ai.RefusalReason;
import com.milviv.domain.exception.AiRequestException;

class AiRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final AiRequest.Usage USAGE = new AiRequest.Usage(1, 10, 5, 300);

    private static AiRequest pending() {
        return AiRequest.start(UUID.randomUUID(), "key-1", "hash", 12, "hash", "assistant.v1", "assistant@1",
                "fake", "fake-1", NOW);
    }

    @Test
    void outcomes_leavePendingOnce() {
        AiRequest answered = pending();
        answered.answered("Ответ", USAGE);
        assertThat(answered.getStatus()).isEqualTo(AiRequestStatus.ANSWERED);
        assertThat(answered.getAiCalls()).isEqualTo(1);
        assertThatThrownBy(() -> answered.failed(AiRequestException.Code.AI_TIMEOUT, USAGE))
                .isInstanceOf(IllegalStateException.class);

        AiRequest clarification = pending();
        clarification.clarificationNeeded("Какой проект?", USAGE);
        assertThat(clarification.getStatus()).isEqualTo(AiRequestStatus.NEEDS_CLARIFICATION);
        assertThat(clarification.getClarificationQuestion()).isEqualTo("Какой проект?");
    }

    @Test
    void refusal_isFailureWithReason() {
        AiRequest refused = pending();
        refused.refused(RefusalReason.UNSUPPORTED_OPERATION, USAGE);

        assertThat(refused.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(refused.getErrorCode()).isEqualTo("AI_REFUSED");
        assertThat(refused.getRefusalReason()).isEqualTo(RefusalReason.UNSUPPORTED_OPERATION);
    }

    @Test
    void stale_onlyWhenPendingLongerThanTimeout() {
        AiRequest request = pending();
        Duration timeout = Duration.ofSeconds(90);

        assertThat(request.isStale(NOW.plusSeconds(90), timeout)).isFalse();
        assertThat(request.isStale(NOW.plusSeconds(91), timeout)).isTrue();

        request.answered("Ответ", USAGE);
        assertThat(request.isStale(NOW.plusSeconds(1000), timeout)).isFalse();
    }
}
