package com.milviv.infrastructure.http.router;

import java.lang.reflect.Method;

import com.milviv.infrastructure.exception.RouterConfigurationException;
import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.RequestAuthenticator;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Delete;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Patch;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.infrastructure.http.annotations.Public;
import com.milviv.infrastructure.http.annotations.Put;

public class RouterScanner implements RouterScannerInterface {

    private final RouterInterface router;
    private final RequestAuthenticator authenticator;

    /**
     * @param authenticator проверка входа; вызывается перед каждым обработчиком без @Public
     */
    public RouterScanner(RouterInterface router, RequestAuthenticator authenticator) {
        this.router = router;
        this.authenticator = authenticator;
    }

    @Override
    public void scan(Object... controllers) {
        for (Object controller : controllers) {
            scanOne(controller);
        }
    }

    private void validateMethod(Method method) throws IllegalStateException {
        if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].equals(HttpContext.class)) {
            throw new RouterConfigurationException("The method " + method.getName() + " must only accept an HttpContext argument");
        }
    }

    private void scanOne(Object controller) {

        String prefix = "";

        if (controller.getClass().isAnnotationPresent(Controller.class)) {
            prefix = controller.getClass().getAnnotation(Controller.class).value();
        }

        for (Method method : controller.getClass().getDeclaredMethods()) {
            boolean isHandler = method.isAnnotationPresent(Get.class) || method.isAnnotationPresent(Post.class) || method.isAnnotationPresent(Put.class) || method.isAnnotationPresent(Delete.class) || method.isAnnotationPresent(Patch.class);

            if (!isHandler) {
                continue;
            }

            validateMethod(method);

            // Закрыто по умолчанию: без @Public обработчик вызывается только после проверки входа
            RouteHandler handler = method.isAnnotationPresent(Public.class)
                    ? ctx -> method.invoke(controller, ctx)
                    : ctx -> {
                        authenticator.authenticate(ctx);
                        method.invoke(controller, ctx);
                    };

            if (method.isAnnotationPresent(Get.class)) {
                String path = method.getAnnotation(Get.class).value();
                router.get(prefix + path, handler);
            }

            if (method.isAnnotationPresent(Post.class)) {
                String path = method.getAnnotation(Post.class).value();
                router.post(prefix + path, handler);
            }

            if (method.isAnnotationPresent(Put.class)) {
                String path = method.getAnnotation(Put.class).value();
                router.put(prefix + path, handler);
            }

            if (method.isAnnotationPresent(Patch.class)) {
                String path = method.getAnnotation(Patch.class).value();
                router.patch(prefix + path, handler);
            }

            if (method.isAnnotationPresent(Delete.class)) {
                String path = method.getAnnotation(Delete.class).value();
                router.delete(prefix + path, handler);
            }

        }
    }

}
