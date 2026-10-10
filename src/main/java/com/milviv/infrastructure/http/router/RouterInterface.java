package com.milviv.infrastructure.http.router;

public interface RouterInterface {
    void get(String path, RouteHandler handler);
    void post(String path, RouteHandler handler);
    void put(String path, RouteHandler handler);
    void patch(String path, RouteHandler handler);
    void delete(String path, RouteHandler handler);
}
