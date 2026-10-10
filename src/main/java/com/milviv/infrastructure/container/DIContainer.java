package com.milviv.infrastructure.container;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DIContainer implements DIContainerInterface {

    private record Binding(Class<?> impl, Scope scope) {

    }

    private final Map<Class<?>, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();
    private final ThreadLocal<LinkedHashSet<Class<?>>> resolving = ThreadLocal.withInitial(() -> new LinkedHashSet<Class<?>>());

    @Override
    public DIContainerInterface register(Class<?> iface, Class<?> impl) throws DIContainerException {
        return register(iface, impl, Scope.SINGLETON);
    }

    @Override
    public DIContainerInterface register(Class<?> impl, Scope scope) throws DIContainerException {
        return register(impl, impl, scope);
    }

    @Override
    public DIContainerInterface register(Class<?> impl) throws DIContainerException {
        return register(impl, impl, Scope.SINGLETON);
    }

    @Override
    public DIContainerInterface register(Class<?> iface, Class<?> impl, Scope scope) throws DIContainerException {

        if (bindings.containsKey(iface)) {
            throw new DIContainerException("Class " + iface.getName() + " is already registered");
        }

        bindings.put(iface, new Binding(impl, scope));

        return this;
    }

    @Override
    public DIContainerInterface singletone(Class<?> iface, Object instance) throws DIContainerException {
        if (singletons.containsKey(iface)) {
            throw new DIContainerException("Class " + iface.getName() + " is already registered");
        }

        bindings.put(iface, new Binding(iface, Scope.SINGLETON));
        singletons.put(iface, instance);

        return this;
    }

    @Override
    public <T> T get(Class<T> type) throws DIContainerException {
        return (T) resolve(type);
    }

    private Object resolve(Class<?> type) throws DIContainerException {
        Binding binding = bindings.get(type);
        if (binding == null) {
            throw new DIContainerException("Type " + type.getName() + " not registered in DI container");
        }

        // Быстрый путь — синглтон уже создан, возвращаем без блокировок
        if (binding.scope() == Scope.SINGLETON) {
            Object existing = singletons.get(type);
            if (existing != null) {
                return existing;
            }
        }

        LinkedHashSet<Class<?>> stack = resolving.get();

        // Проверяем ДО создания — цикл происходит внутри createInstance() через рекурсию
        if (!stack.add(type)) {
            throw new DIContainerException(buildCycleMessage(stack, type));
        }

        try {
            Object instance = this.createInstance(binding.impl());

            if (binding.scope() == Scope.SINGLETON) {
                // putIfAbsent гарантирует: при гонке двух потоков выигрывает первый,
                // singletons.get возвращает всегда один и тот же объект
                singletons.putIfAbsent(type, instance);
                return singletons.get(type);
            }

            return instance;
        } catch (DIContainerException e) {
            throw e;
        } catch (Exception e) {
            throw new DIContainerException("Failed to create " + type.getName() + ": " + e.getMessage());
        } finally {
            stack.remove(type);
            if (stack.isEmpty()) {
                resolving.remove();
            }
        }
    }

    private String buildCycleMessage(LinkedHashSet<Class<?>> stack, Class<?> duplicate) {
        StringBuilder sb = new StringBuilder("Circular dependency detected\n  ");
        stack.forEach((c) -> sb.append(c.getSimpleName()).append(" → "));

        sb.append(duplicate.getSimpleName()).append(" ← cycle here");

        return sb.toString();
    }

    private Object createInstance(Class<?> type) throws DIContainerException {
        Constructor<?> ctor = selectConstructor(type);
        ctor.setAccessible((true));

        Class<?>[] paramTypes = ctor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = resolve(paramTypes[i]);
        }

        try {
            return ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new DIContainerException(
                    "Constructor of " + ctor.getName() + " threw an exception " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
            );
        } catch (InstantiationException | IllegalAccessException e) {
            throw new DIContainerException(
                    "Failed to instantiate " + type.getName() + ": " + e.getMessage()
            );
        }
    }

    private Constructor<?> selectConstructor(Class<?> type) throws DIContainerException {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        Constructor<?> annotatedCtor = Arrays.stream(constructors)
                .filter((ctor) -> ctor.isAnnotationPresent(Inject.class))
                .findFirst()
                .orElse(null);

        if (annotatedCtor != null) {
            return annotatedCtor;
        }

        if (constructors.length == 1) {
            return constructors[0];
        }

        throw new DIContainerException("The " + type.getName() + " class uses multiple constructors without @Inject. Add the Inject annotation to the constructor that requires dependency injection container.");
    }

}
