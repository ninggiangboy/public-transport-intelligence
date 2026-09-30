package dev.pti.api.platform.adapter.in.security;

import dev.pti.api.platform.adapter.in.web.ProblemWriter;
import dev.pti.api.platform.domain.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 403 as Problem Details (DOC-30 §3.2): a caller with a valid token but without the role. */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemAccessDeniedHandler.class);

    private final ProblemWriter problems;

    public ProblemAccessDeniedHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        log.info("Access denied for {} {}", request.getMethod(), request.getRequestURI());
        problems.write(request, response, ProblemType.FORBIDDEN);
    }
}
