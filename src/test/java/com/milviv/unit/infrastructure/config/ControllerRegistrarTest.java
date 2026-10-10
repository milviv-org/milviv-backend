package com.milviv.unit.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milviv.infrastructure.config.ControllerRegistrar;
import com.milviv.infrastructure.container.DIContainerException;
import com.milviv.infrastructure.container.DIContainerInterface;
import com.milviv.infrastructure.container.Scope;
import com.milviv.infrastructure.http.router.RouterScannerInterface;

class ControllerRegistrarTest {

    // ─── Test fixtures ────────────────────────────────────────────────────────

    static class FakeController {}
    static class AnotherController {}

    // ─── Setup ────────────────────────────────────────────────────────────────

    private DIContainerInterface container;
    private RouterScannerInterface routerScanner;
    private ControllerRegistrar registrar;

    @BeforeEach
    void setUp() {
        container = mock(DIContainerInterface.class);
        routerScanner = mock(RouterScannerInterface.class);
        registrar = new ControllerRegistrar(container, routerScanner);
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void register_shouldRegisterEachControllerInContainer() throws DIContainerException {
        // given
        FakeController fakeInstance = new FakeController();
        AnotherController anotherInstance = new AnotherController();

        when(container.register(FakeController.class, Scope.SINGLETON)).thenReturn(container);
        when(container.register(AnotherController.class, Scope.SINGLETON)).thenReturn(container);
        when(container.get(FakeController.class)).thenReturn(fakeInstance);
        when(container.get(AnotherController.class)).thenReturn(anotherInstance);

        // when
        registrar.register(FakeController.class, AnotherController.class);

        // then
        verify(container).register(FakeController.class, Scope.SINGLETON);
        verify(container).register(AnotherController.class, Scope.SINGLETON);
    }

    @Test
    void register_shouldPassInstancesToRouterScanner() throws DIContainerException {
        // given
        FakeController fakeInstance = new FakeController();

        when(container.register(FakeController.class, Scope.SINGLETON)).thenReturn(container);
        when(container.get(FakeController.class)).thenReturn(fakeInstance);

        // when
        registrar.register(FakeController.class);

        // then
        verify(routerScanner).scan(fakeInstance);
    }

    @Test
    void register_shouldThrow_whenContainerRegisterFails() throws DIContainerException {
        // given
        doThrow(new DIContainerException("Registration failed"))
                .when(container).register(eq(FakeController.class), any(Scope.class));

        // when / then
        assertThatThrownBy(() -> registrar.register(FakeController.class))
                .isInstanceOf(DIContainerException.class)
                .hasMessageContaining("Registration failed");
    }

    @Test
    void register_shouldThrow_whenContainerGetFails() throws DIContainerException {
        // given
        when(container.register(FakeController.class, Scope.SINGLETON)).thenReturn(container);
        doThrow(new DIContainerException("Bean not found"))
                .when(container).get(FakeController.class);

        // when / then
        assertThatThrownBy(() -> registrar.register(FakeController.class))
                .isInstanceOf(DIContainerException.class)
                .hasMessageContaining("Bean not found");
    }

    @Test
    void register_withNoControllers_shouldNotInteractWithRouterScanner() throws DIContainerException {
        // when
        registrar.register();

        // then
        verify(routerScanner).scan(new Object[0]);
    }
}
