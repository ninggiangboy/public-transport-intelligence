package dev.pti.simulator.control;

import dev.pti.simulator.scenario.ScenarioException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors of the control API as RFC 9457 Problem Details with {@code urn:pti:problem:<slug>} (DOC-30 §3). */
@RestControllerAdvice
public class ProblemHandler {

    static final URI INVALID_PARAM = URI.create("urn:pti:problem:invalid-param");

    @ExceptionHandler
    ProblemDetail invalidParam(InvalidParamException e, HttpServletRequest request) {
        return invalidParam(e.getMessage(), e.errors(), request);
    }

    @ExceptionHandler
    ProblemDetail scenario(ScenarioException e, HttpServletRequest request) {
        ScenarioException.Problem p = e.problem();
        List<InvalidParamException.FieldError> errors = e.errors().stream()
                .map(f -> new InvalidParamException.FieldError(f.field(), f.message()))
                .toList();
        if (p == ScenarioException.Problem.INVALID_PARAM) {
            return invalidParam(e.getMessage(), errors, request);
        }
        return problem(HttpStatus.valueOf(p.status()), p.slug(), p.title(), e.getMessage(), request);
    }

    /** {@code PUT /sim/rate} while {@code load-ramp} owns the multipliers (DOC-25 §7.8). */
    @ExceptionHandler
    ProblemDetail loadRamp(LoadRampRunningException e, HttpServletRequest request) {
        ScenarioException.Problem p = ScenarioException.Problem.LOAD_RAMP_RUNNING;
        return problem(HttpStatus.valueOf(p.status()), p.slug(), p.title(), e.getMessage(), request);
    }

    @ExceptionHandler
    ProblemDetail unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return invalidParam("The request body is not valid JSON of the expected shape.", List.of(), request);
    }

    private static ProblemDetail invalidParam(
            String detail, List<InvalidParamException.FieldError> errors, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-param", "Invalid parameter", detail, request);
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(
            HttpStatus status, String slug, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:pti:problem:" + slug));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
