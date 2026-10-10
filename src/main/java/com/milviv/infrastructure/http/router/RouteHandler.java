package com.milviv.infrastructure.http.router;

import com.milviv.infrastructure.http.HttpContext;

@FunctionalInterface
public interface RouteHandler {

    void handle(HttpContext ctx) throws Exception;
}
