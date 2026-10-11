package com.milviv.presentation.rest.controllers;

import java.util.Map;

import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;
import com.milviv.infrastructure.http.annotations.Public;

@Controller("/health")
public class HealthController {

    @Public
    @Get("")
    public void health(HttpContext ctx) throws Exception {
        ctx.json(200, Map.of("status", "ok"));
    }
}
