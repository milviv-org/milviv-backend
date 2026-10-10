package com.milviv.infrastructure.container;

public class DIContainerException extends RuntimeException {

    public DIContainerException(String message) {
        super("[DIContainer] " + message);
    }

}
