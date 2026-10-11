package com.milviv.infrastructure.http;

import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milviv.domain.exception.UnauthorizedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class HttpContext {

    private static final String USER_ID_ATTRIBUTE = HttpContext.class.getName() + ".userId";

    private final ObjectMapper objectMapper;

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final HttpContextValidatorInterface httpContextValidator;

    public HttpContext(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpContextValidatorInterface httpContextValidator
    ) {
        this.request = request;
        this.response = response;
        this.objectMapper = objectMapper;
        this.httpContextValidator = httpContextValidator;
    }

    public void json(int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    public <T> T body(Class<T> type) throws IOException {
        String json = request.getReader().lines().collect(Collectors.joining());
        T obj = objectMapper.readValue(json, type);
        this.httpContextValidator.validate(obj);

        return obj;
    }

    public String pathParam(String name) {
        return (String) this.request.getAttribute(name);
    }

    public String pathQuery(String name) {
        return request.getParameter(name);
    }

    /** Ответ без тела, например 204 No Content. */
    public void status(int status) {
        response.setStatus(status);
    }

    /** Вызывается RequestAuthenticator после проверки токена. */
    public void authenticateAs(UUID userId) {
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
    }

    /** Id вошедшего пользователя. В @Public-маршрутах входа нет — бросает UnauthorizedException. */
    public UUID userId() {
        Object userId = request.getAttribute(USER_ID_ATTRIBUTE);
        if (userId instanceof UUID id) {
            return id;
        }
        throw new UnauthorizedException("Требуется вход");
    }

    public HttpServletRequest getRequest() {
        return this.request;
    }

    public HttpServletResponse getResponse() {
        return this.response;
    }

}
