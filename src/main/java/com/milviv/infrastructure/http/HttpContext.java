package com.milviv.infrastructure.http;

import java.io.IOException;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class HttpContext {

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

    public HttpServletRequest getRequest() {
        return this.request;
    }

    public HttpServletResponse getResponse() {
        return this.response;
    }

}
