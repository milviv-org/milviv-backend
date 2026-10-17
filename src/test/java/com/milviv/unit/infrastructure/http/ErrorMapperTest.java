package com.milviv.unit.infrastructure.http;

import com.milviv.domain.exception.AiRequestException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milviv.domain.exception.AppException;
import com.milviv.domain.exception.FieldError;
import com.milviv.domain.exception.ValidationException;
import com.milviv.infrastructure.http.ErrorMapper;
import com.milviv.infrastructure.http.ErrorResponse;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.HttpStatusResolver;

class ErrorMapperTest {

    private ErrorMapper errorMapper;
    private HttpContext ctx;
    private HttpStatusResolver httpStatusResolver;

    @BeforeEach
    void setUp() {
        httpStatusResolver = mock(HttpStatusResolver.class);
        errorMapper = new ErrorMapper(httpStatusResolver);
        ctx = mock(HttpContext.class);
    }

    // ─── AppException ─────────────────────────────────────────────────────────

    @Test
    void handle_shouldRespondWithAppExceptionStatusAndCode() throws IOException {
        // given
        AppException appEx = new AppException("Not found") {
            @Override public String getErrorCode() { return "NOT_FOUND"; }
        };
        when(httpStatusResolver.resolve(appEx)).thenReturn(404);
        ArgumentCaptor<ErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ErrorResponse.class);

        // when
        errorMapper.handle(appEx, ctx);

        // then
        verify(ctx).json(eq(404), bodyCaptor.capture());
        ErrorResponse response = bodyCaptor.getValue();
        assertThat(response.errorCode).isEqualTo("NOT_FOUND");
        assertThat(response.message).isEqualTo("Not found");
    }

    @Test
    void handle_shouldIncludeErrors_whenValidationException() throws IOException {
        // given
        ValidationException appEx = new ValidationException("email", "must not be blank");
        when(httpStatusResolver.resolve(appEx)).thenReturn(422);
        ArgumentCaptor<ErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ErrorResponse.class);

        // when
        errorMapper.handle(appEx, ctx);

        // then
        verify(ctx).json(eq(422), bodyCaptor.capture());
        List<?> errors = (List<?>) bodyCaptor.getValue().extension.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(((FieldError) errors.get(0)).field()).isEqualTo("email");
        assertThat(((FieldError) errors.get(0)).message()).isEqualTo("must not be blank");
    }

    // ─── InvocationTargetException ────────────────────────────────────────────

    @Test
    void handle_shouldUnwrapInvocationTargetException_whenCauseIsAppException() throws IOException {
        // given
        AppException appEx = new AppException("Forbidden") {
            @Override public String getErrorCode() { return "FORBIDDEN"; }
        };
        when(httpStatusResolver.resolve(appEx)).thenReturn(403);
        InvocationTargetException wrapped = new InvocationTargetException(appEx);
        ArgumentCaptor<ErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ErrorResponse.class);

        // when
        errorMapper.handle(wrapped, ctx);

        // then
        verify(ctx).json(eq(403), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().errorCode).isEqualTo("FORBIDDEN");
    }

    // ─── Generic exception ────────────────────────────────────────────────────

    @Test
    void handle_shouldRespondWith500_forUnknownException() throws IOException {
        // given
        RuntimeException ex = new RuntimeException("Something went wrong");
        ArgumentCaptor<ErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ErrorResponse.class);

        // when
        errorMapper.handle(ex, ctx);

        // then
        verify(ctx).json(eq(500), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().errorCode).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @Test
    void handle_shouldRespondWith500_forWrappedUnknownException() throws IOException {
        // given
        InvocationTargetException wrapped = new InvocationTargetException(new NullPointerException("null"));
        ArgumentCaptor<ErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ErrorResponse.class);

        // when
        errorMapper.handle(wrapped, ctx);

        // then
        verify(ctx).json(eq(500), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().errorCode).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    // ─── retryAfter (квоты AI) ────────────────────────────────────────────────

    @Test
    void handle_shouldAddRetryAfter_secondsRoundedUp() throws IOException {
        jakarta.servlet.http.HttpServletResponse response = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(ctx.getResponse()).thenReturn(response);
        AiRequestException ex = AiRequestException.quotaExceeded(java.time.Duration.ofMillis(41_200));
        when(httpStatusResolver.resolve(ex)).thenReturn(429);
        ArgumentCaptor<ErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ErrorResponse.class);

        errorMapper.handle(ex, ctx);

        verify(ctx).json(eq(429), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().errorCode).isEqualTo("AI_QUOTA_EXCEEDED");
        assertThat(bodyCaptor.getValue().extension).containsEntry("retryAfterSeconds", 42L);
        verify(response).setHeader("Retry-After", "42");
    }

    @Test
    void handle_shouldNotAddRetryAfter_whenUnknown() throws IOException {
        AiRequestException ex = new AiRequestException(AiRequestException.Code.AI_QUOTA_EXCEEDED);
        when(httpStatusResolver.resolve(ex)).thenReturn(429);
        ArgumentCaptor<ErrorResponse> bodyCaptor = ArgumentCaptor.forClass(ErrorResponse.class);

        errorMapper.handle(ex, ctx);

        verify(ctx).json(eq(429), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().extension).isNull();
        org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never()).getResponse();
    }
}
