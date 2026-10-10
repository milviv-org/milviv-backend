package com.milviv.infrastructure.config;

import com.milviv.infrastructure.container.DIContainerException;
import com.milviv.infrastructure.container.DIContainerInterface;
import com.milviv.infrastructure.container.Scope;
import com.milviv.infrastructure.http.router.RouterScannerInterface;

public class ControllerRegistrar {

    private final DIContainerInterface container;
    private final RouterScannerInterface routerScanner;

    public ControllerRegistrar(DIContainerInterface container, RouterScannerInterface routerScanner) {
        this.container = container;
        this.routerScanner = routerScanner;
    }

    public void register(Class<?>... controllers) throws DIContainerException {
        for (Class<?> controller : controllers) {
            this.container.register(controller, Scope.SINGLETON);
        }

        Object[] instances = new Object[controllers.length];
        for (int i = 0; i < controllers.length; i++) {
            instances[i] = container.get(controllers[i]);
        }

        routerScanner.scan(instances);
    }
}
