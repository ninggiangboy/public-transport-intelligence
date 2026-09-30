package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a Problem Details response from code that runs outside the controllers: the security entry point and denied
 * handler, the webhook filter, the rate limiter and the body size filter. The controllers' own errors leave through
 * the advice with the same body.
 */
public final class ProblemWriter {

    private final ProblemFactory problems;
    private final JsonMapper mapper;

    public ProblemWriter(ProblemFactory problems, JsonMapper mapper) {
        this.problems = problems;
        this.mapper = mapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemType type) throws IOException {
        write(request, response, type, null, Map.of(), Map.of());
    }

    /**
     * @param headers extra response headers such as {@code WWW-Authenticate} or {@code Retry-After}
     */
    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ProblemType type,
            String detail,
            Map<String, Object> extensions,
            Map<String, String> headers)
            throws IOException {
        Map<String, Object> body = problems.body(type, detail, request.getRequestURI(), List.of(), extensions);
        response.setStatus(type.status());
        headers.forEach(response::setHeader);
        response.setContentType(ProblemFactory.PROBLEM_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
