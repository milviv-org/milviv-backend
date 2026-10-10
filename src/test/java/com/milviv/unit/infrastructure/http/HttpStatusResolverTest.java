package com.milviv.unit.infrastructure.http;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

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
}
