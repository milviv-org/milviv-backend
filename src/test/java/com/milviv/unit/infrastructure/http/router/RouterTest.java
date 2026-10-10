package com.milviv.unit.infrastructure.http.router;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milviv.infrastructure.config.JacksonConfig;
import com.milviv.infrastructure.http.ErrorMapper;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.HttpContextValidatorInterface;
import com.milviv.infrastructure.http.HttpStatusResolver;
import com.milviv.infrastructure.http.router.RouteHandler;
import com.milviv.infrastructure.http.router.Router;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class RouterTest {

    private Router router;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new JacksonConfig().getObjectMapper();
        router = new Router(new ErrorMapper(new HttpStatusResolver()), objectMapper, mock(HttpContextValidatorInterface.class));
    }

    // ─── Exact routes ─────────────────────────────────────────────────────────

    @Test
    void get_shouldDispatchToRegisteredHandler() throws Exception {
        // given
        RouteHandler handler = mock(RouteHandler.class);
        router.get("/health", handler);

        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when
        router.handle("/health", baseRequest, request, response);

        // then
        verify(handler).handle(any(HttpContext.class));
        verify(baseRequest).setHandled(true);
    }

    @Test
    void post_shouldDispatchToRegisteredHandler() throws Exception {
        // given
        RouteHandler handler = mock(RouteHandler.class);
        router.post("/api/tasks", handler);

        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("POST");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when
        router.handle("/api/tasks", baseRequest, request, response);

        // then
        verify(handler).handle(any(HttpContext.class));
    }

    @Test
    void handle_shouldReturn404_whenRouteNotFound() throws Exception {
        // given
        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when
        router.handle("/unknown", baseRequest, request, response);

        // then
        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        verify(baseRequest).setHandled(true);
        assertThat(writer.toString()).contains("NOT_FOUND");
    }

    @Test
    void handle_shouldReturn404_whenMethodDoesNotMatch() throws Exception {
        // given
        router.get("/api/tasks", mock(RouteHandler.class));

        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("POST"); // зарегистрирован GET, приходит POST
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when
        router.handle("/api/tasks", baseRequest, request, response);

        // then
        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void handle_shouldReturn500_whenHandlerThrowsException() throws Exception {
        // given
        RouteHandler handler = (ctx) -> { throw new RuntimeException("Unexpected error"); };
        router.get("/boom", handler);

        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when
        router.handle("/boom", baseRequest, request, response);

        // then
        verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertThat(writer.toString()).contains("Internal server error");
    }

    // ─── Dynamic routes ───────────────────────────────────────────────────────

    @Test
    void get_shouldDispatchDynamicRoute_andExtractPathParam() throws Exception {
        // given
        RouteHandler handler = mock(RouteHandler.class);
        router.get("/api/tasks/{id}", handler);

        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when
        router.handle("/api/tasks/42", baseRequest, request, response);

        // then
        verify(handler).handle(any(HttpContext.class));
        verify(request).setAttribute("id", "42");
    }

    @Test
    void handle_shouldExtractMultiplePathParams() throws Exception {
        // given
        RouteHandler handler = mock(RouteHandler.class);
        router.get("/api/users/{userId}/tasks/{taskId}", handler);

        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when
        router.handle("/api/users/5/tasks/99", baseRequest, request, response);

        // then
        verify(request).setAttribute("userId", "5");
        verify(request).setAttribute("taskId", "99");
    }

    @Test
    void exactRoute_shouldTakePriorityOverDynamicRoute() throws Exception {
        // given
        RouteHandler exactHandler = mock(RouteHandler.class);
        RouteHandler dynamicHandler = mock(RouteHandler.class);

        router.get("/api/tasks/export", exactHandler);   // точный
        router.get("/api/tasks/{id}", dynamicHandler);   // динамический

        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when
        router.handle("/api/tasks/export", baseRequest, request, response);

        // then — должен вызваться exactHandler, НЕ dynamicHandler
        verify(exactHandler).handle(any(HttpContext.class));
    }

    @Test
    void dynamicRoute_shouldNotMatch_whenSegmentCountDiffers() throws Exception {
        // given
        router.get("/api/tasks/{id}", mock(RouteHandler.class));

        Request baseRequest = mock(Request.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();

        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        // when — путь с лишним сегментом
        router.handle("/api/tasks/42/comments", baseRequest, request, response);

        // then
        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
}
