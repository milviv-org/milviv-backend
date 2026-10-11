package com.milviv.unit.infrastructure.http;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.milviv.domain.exception.ValidationException;
import com.milviv.infrastructure.config.JacksonConfig;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.HttpContextValidatorInterface;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class HttpContextBodyTest {

    record Payload(String name, LocalDate deadline, Integer points) {}

    private static HttpContext context(String body) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        return new HttpContext(request, mock(HttpServletResponse.class),
                new JacksonConfig().getObjectMapper(), mock(HttpContextValidatorInterface.class));
    }

    @Test
    void body_shouldParseValidJson() throws Exception {
        Payload payload = context("{\"name\":\"x\",\"deadline\":\"2026-10-11\",\"points\":5}").body(Payload.class);

        assertThat(payload.deadline()).isEqualTo(LocalDate.parse("2026-10-11"));
    }

    @Test
    void body_shouldReturnValidationError_forMalformedJson() {
        assertThatThrownBy(() -> context("{\"name\":").body(Payload.class))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getErrors().getFirst().field()).isEqualTo("body"));
    }

    @Test
    void body_shouldNameField_whenValueHasWrongType() {
        assertThatThrownBy(() -> context("{\"deadline\":\"11.10.2026\"}").body(Payload.class))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getErrors().getFirst().field()).isEqualTo("deadline"));
        assertThatThrownBy(() -> context("{\"points\":\"много\"}").body(Payload.class))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getErrors().getFirst().field()).isEqualTo("points"));
    }

    @Test
    void body_shouldRequireBody() {
        assertThatThrownBy(() -> context("null").body(Payload.class)).isInstanceOf(ValidationException.class);
    }
}
