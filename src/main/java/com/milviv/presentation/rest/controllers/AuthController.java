package com.milviv.presentation.rest.controllers;

import com.milviv.infrastructure.http.HttpContext;
import com.milviv.infrastructure.http.annotations.Controller;
import com.milviv.infrastructure.http.annotations.Post;
import com.milviv.shared.validation.annotation.annotations.Email;
import com.milviv.shared.validation.annotation.annotations.MaxLength;
import com.milviv.shared.validation.annotation.annotations.MinLength;
import com.milviv.shared.validation.annotation.annotations.Required;

@Controller("/auth")
public class AuthController {

  record AuthenticateRequest(
      @Required
      @Email
      String email,

      @Required
      @MinLength(length = 6)
      @MaxLength(length = 64)
      String password
  ) {}

  @Post("/authenticate")
  public void authenticate(HttpContext ctx) throws Exception {
    AuthenticateRequest body = ctx.body(AuthenticateRequest.class);
    ctx.json(200, body);
  }

  @Post("/registration")
  public void registration(HttpContext ctx) {
  }

}
