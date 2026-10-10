package com.milviv.infrastructure.container;

public interface DIContainerInterface {

    DIContainerInterface register(Class<?> iface, Class<?> impl, Scope scope) throws DIContainerException;

    DIContainerInterface register(Class<?> iface, Class<?> impl) throws DIContainerException;

    DIContainerInterface register(Class<?> impl, Scope scope) throws DIContainerException;

    DIContainerInterface register(Class<?> impl) throws DIContainerException;

    DIContainerInterface singletone(Class<?> iface, Object instance) throws DIContainerException;

    <T> T get(Class<T> iface) throws DIContainerException;
}
