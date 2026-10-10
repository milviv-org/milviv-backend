package com.milviv.presentation.rest.controllers;

import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Get;

@Controller("/user-context")
public class UserContextController {

  @Get("/")
  public void getUserContext(HttpContext ctx) {

  }
}
