package dev.pti.simulator.control;

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
    ProblemDetail unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return invalidParam("The request body is not valid JSON of the expected shape.", List.of(), request);
    }

    private static ProblemDetail invalidParam(
            String detail, List<InvalidParamException.FieldError> errors, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(INVALID_PARAM);
        problem.setTitle("Invalid parameter");
        problem.setInstance(URI.create(request.getRequestURI()));
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        problem.setProperty("errors", errors);
        return problem;
    }
}
