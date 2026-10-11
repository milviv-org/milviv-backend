package com.milviv.unit.infrastructure.http;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.milviv.domain.exception.AiConsentRequiredException;
import com.milviv.domain.exception.AiRequestException;
import com.milviv.domain.exception.AppException;
import com.milviv.domain.exception.OptimisticLockException;
import com.milviv.infrastructure.http.HttpStatusResolver;

class HttpStatusResolverTest {

    private final HttpStatusResolver resolver = new HttpStatusResolver();

    @Test
    void resolve_shouldReturnConflict_whenOptimisticLock() {
        // given
        OptimisticLockException ex = new OptimisticLockException("User", UUID.randomUUID(), 1);

        // when / then
        assertThat(resolver.resolve(ex)).isEqualTo(409);
    }

    @Test
    void resolve_shouldReturnInternalError_whenErrorCodeUnknown() {
        // given
        AppException ex = new AppException("unknown") {
            @Override public String getErrorCode() { return "SOMETHING_NEW"; }
        };

        // when / then
        assertThat(resolver.resolve(ex)).isEqualTo(500);
    }

    @Test
    void resolve_shouldReturnForbidden_whenAiConsentRequired() {
        assertThat(resolver.resolve(new AiConsentRequiredException())).isEqualTo(403);
    }

    @Test
    void resolve_shouldMapEveryAiRequestCode() {
        for (AiRequestException.Code code : AiRequestException.Code.values()) {
            assertThat(resolver.resolve(new AiRequestException(code))).as(code.name()).isNotEqualTo(500);
        }
        assertThat(resolver.resolve(new AiRequestException(AiRequestException.Code.AI_REQUEST_IN_PROGRESS))).isEqualTo(409);
        assertThat(resolver.resolve(new AiRequestException(AiRequestException.Code.IDEMPOTENCY_KEY_REUSED))).isEqualTo(422);
        assertThat(resolver.resolve(new AiRequestException(AiRequestException.Code.AI_PROVIDER_UNAVAILABLE))).isEqualTo(503);
        assertThat(resolver.resolve(new AiRequestException(AiRequestException.Code.AI_TIMEOUT))).isEqualTo(504);
        assertThat(resolver.resolve(new AiRequestException(AiRequestException.Code.AI_INVALID_RESPONSE))).isEqualTo(502);
        assertThat(resolver.resolve(new AiRequestException(AiRequestException.Code.AI_REFUSED))).isEqualTo(422);
    }
}
