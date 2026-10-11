package com.milviv.unit.infrastructure.http.router;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.milviv.domain.exception.UnauthorizedException;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.RequestAuthenticator;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Public;
import com.milviv.infrastructure.http.router.RouteHandler;
import com.milviv.infrastructure.http.router.RouterInterface;
import com.milviv.infrastructure.http.router.RouterScanner;

class RouterScannerAuthTest {

    @Controller("/t")
    public static class SampleController {
        final AtomicBoolean publicCalled = new AtomicBoolean();
        final AtomicBoolean privateCalled = new AtomicBoolean();

        @Public
        @Get("/open")
        public void open(HttpContext ctx) { publicCalled.set(true); }

        @Get("/closed")
        public void closed(HttpContext ctx) { privateCalled.set(true); }
    }

    private final Map<String, RouteHandler> routes = new HashMap<>();
    private final SampleController controller = new SampleController();
    private boolean authenticated;

    @BeforeEach
    void setUp() {
        RouterInterface router = new RouterInterface() {
            @Override public void get(String path, RouteHandler handler) { routes.put(path, handler); }
            @Override public void post(String path, RouteHandler handler) { routes.put(path, handler); }
            @Override public void put(String path, RouteHandler handler) { routes.put(path, handler); }
            @Override public void patch(String path, RouteHandler handler) { routes.put(path, handler); }
            @Override public void delete(String path, RouteHandler handler) { routes.put(path, handler); }
        };
        RequestAuthenticator authenticator = ctx -> {
            if (!authenticated) {
                throw new UnauthorizedException("Требуется вход");
            }
            ctx.authenticateAs(UUID.randomUUID());
        };
        new RouterScanner(router, authenticator).scan(controller);
    }

    @Test
    void publicRoute_shouldRunWithoutAuthentication() throws Exception {
        routes.get("/t/open").handle(mock(HttpContext.class));

        assertThat(controller.publicCalled).isTrue();
    }

    @Test
    void protectedRoute_shouldRejectAnonymous_withoutCallingHandler() {
        assertThatThrownBy(() -> routes.get("/t/closed").handle(mock(HttpContext.class)))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(controller.privateCalled).isFalse();
    }

    @Test
    void protectedRoute_shouldRun_whenAuthenticated() throws Exception {
        authenticated = true;

        routes.get("/t/closed").handle(mock(HttpContext.class));

        assertThat(controller.privateCalled).isTrue();
    }
}
