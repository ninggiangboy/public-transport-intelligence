package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.ProblemType;
import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.ErrorKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every exception of a controller into Problem Details (DOC-30 §3.3): the project's {@link ApiException}s by
 * their own type, the framework's exceptions by the table of DOC-30, infrastructure failures that the {@link
 * ErrorClassifier} calls transient as 503, and anything else as a bare 500 that names only the trace id.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** {@code Retry-After} of a transient infrastructure failure (DOC-30 §3.2). */
    static final int TRANSIENT_RETRY_SECONDS = 5;

    private final ProblemFactory problems;
    private final ErrorClassifier classifier;

    public ApiExceptionHandler(ProblemFactory problems, ErrorClassifier classifier) {
        this.problems = problems;
        this.classifier = classifier;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> apiException(ApiException ex, WebRequest request) {
        log.debug("Request rejected with {}", ex.type().slug());
        HttpHeaders headers = new HttpHeaders();
        Map<String, Object> extensions = new LinkedHashMap<>(ex.extensions());
        Integer retryAfter = ex.retryAfterSeconds();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        }
        return respond(ex.type(), ex.getMessage(), ex.errors(), extensions, headers, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldError> errors = ex.getConstraintViolations().stream()
                .map(violation ->
                        new FieldError(lastNode(violation.getPropertyPath().toString()), violation.getMessage()))
                .toList();
        return respond(ProblemType.VALIDATION_ERROR, null, errors, Map.of(), new HttpHeaders(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> authentication(AuthenticationException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return respond(ProblemType.UNAUTHORIZED, null, List.of(), Map.of(), headers, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> accessDenied(AccessDeniedException ex, WebRequest request) {
        return respond(ProblemType.FORBIDDEN, null, List.of(), Map.of(), new HttpHeaders(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        if (classifier.classify(ex) == ErrorKind.TRANSIENT_INFRA) {
            log.warn(
                    "Infrastructure unavailable while handling a request: {}",
                    ex.getClass().getSimpleName());
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(TRANSIENT_RETRY_SECONDS));
            return respond(ProblemType.SERVICE_UNAVAILABLE, null, List.of(), Map.of(), headers, request);
        }
        log.error("Unhandled exception while handling a request", ex);
        return respond(ProblemType.INTERNAL_ERROR, null, List.of(), Map.of(), new HttpHeaders(), request);
    }

    /** Every exception that {@link ResponseEntityExceptionHandler} knows ends here with its status and headers. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemType type = typeOf(ex, statusCode);
        return respond(type, detailOf(ex, type), fieldErrors(ex), Map.of(), headers, request);
    }

    private ResponseEntity<Object> respond(
            ProblemType type,
            @Nullable String detail,
            List<FieldError> errors,
            Map<String, Object> extensions,
            HttpHeaders headers,
            WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        Map<String, Object> body = problems.body(type, detail, servletRequest.getRequestURI(), errors, extensions);
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.remove(HttpHeaders.CONTENT_TYPE);
        out.setContentType(MediaType.parseMediaType(ProblemFactory.PROBLEM_JSON));
        return ResponseEntity.status(type.status()).headers(out).body(body);
    }

    static ProblemType typeOf(Exception ex, HttpStatusCode status) {
        return switch (ex) {
            case MethodArgumentTypeMismatchException mismatch ->
                mismatch.getParameter().hasParameterAnnotation(PathVariable.class)
                        ? ProblemType.NOT_FOUND
                        : ProblemType.VALIDATION_ERROR;
            case MethodArgumentNotValidException ignored -> ProblemType.VALIDATION_ERROR;
            case HandlerMethodValidationException ignored -> ProblemType.VALIDATION_ERROR;
            case TypeMismatchException ignored -> ProblemType.VALIDATION_ERROR;
            case ServletRequestBindingException ignored -> ProblemType.VALIDATION_ERROR;
            case HttpMessageNotReadableException ignored -> ProblemType.VALIDATION_ERROR;
            case NoResourceFoundException ignored -> ProblemType.NOT_FOUND;
            case HttpRequestMethodNotSupportedException ignored -> ProblemType.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotAcceptableException ignored -> ProblemType.NOT_ACCEPTABLE;
            case HttpMediaTypeNotSupportedException ignored -> ProblemType.UNSUPPORTED_MEDIA_TYPE;
            case MaxUploadSizeExceededException ignored -> ProblemType.PAYLOAD_TOO_LARGE;
            default -> byStatus(status.value());
        };
    }

    private static ProblemType byStatus(int status) {
        return switch (status) {
            case 404 -> ProblemType.NOT_FOUND;
            case 405 -> ProblemType.METHOD_NOT_ALLOWED;
            case 406 -> ProblemType.NOT_ACCEPTABLE;
            case 413 -> ProblemType.PAYLOAD_TOO_LARGE;
            case 415 -> ProblemType.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> ProblemType.SERVICE_UNAVAILABLE;
            default -> status >= 500 ? ProblemType.INTERNAL_ERROR : ProblemType.VALIDATION_ERROR;
        };
    }

    /** Fixed sentences: what Spring says about a failed binding can name classes or echo the input. */
    private static @Nullable String detailOf(Exception ex, ProblemType type) {
        if (ex instanceof HttpMessageNotReadableException) {
            return "The request body is not valid JSON of the expected shape.";
        }
        if (ex instanceof MethodArgumentTypeMismatchException && type == ProblemType.NOT_FOUND) {
            return "The requested resource does not exist.";
        }
        return null;
    }

    static List<FieldError> fieldErrors(Exception ex) {
        return switch (ex) {
            case MethodArgumentTypeMismatchException mismatch ->
                mismatch.getParameter().hasParameterAnnotation(PathVariable.class)
                        ? List.of()
                        : List.of(new FieldError(mismatch.getName(), "has an invalid value"));
            case MethodArgumentNotValidException invalid ->
                invalid.getBindingResult().getFieldErrors().stream()
                        .map(error -> new FieldError(error.getField(), messageOf(error)))
                        .toList();
            case HandlerMethodValidationException invalid -> methodValidationErrors(invalid);
            case MissingServletRequestParameterException missing ->
                List.of(new FieldError(missing.getParameterName(), "is required"));
            case TypeMismatchException mismatch ->
                List.of(new FieldError(String.valueOf(mismatch.getPropertyName()), "has an invalid value"));
            case HttpMessageNotReadableException unreadable -> bodyErrors(unreadable);
            default -> List.of();
        };
    }

    private static List<FieldError> methodValidationErrors(HandlerMethodValidationException ex) {
        List<FieldError> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors
                        .getFieldErrors()
                        .forEach(error -> errors.add(new FieldError(error.getField(), messageOf(error))));
            } else {
                String name = result.getMethodParameter().getParameterName();
                for (MessageSourceResolvable resolvable : result.getResolvableErrors()) {
                    errors.add(new FieldError(name != null ? name : "parameter", messageOf(resolvable)));
                }
            }
        }
        return errors;
    }

    /** JSON Pointer of the field that Jackson stopped at, for example {@code /payload/route_id}. */
    private static List<FieldError> bodyErrors(HttpMessageNotReadableException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
                StringBuilder pointer = new StringBuilder();
                for (JacksonException.Reference reference : jackson.getPath()) {
                    pointer.append('/');
                    pointer.append(
                            reference.getPropertyName() != null ? reference.getPropertyName() : reference.getIndex());
                }
                String message = cause instanceof UnrecognizedPropertyException
                        ? "is not a known property"
                        : "has an invalid value";
                return List.of(new FieldError(pointer.toString(), message));
            }
        }
        return List.of();
    }

    private static String messageOf(MessageSourceResolvable resolvable) {
        String message = resolvable.getDefaultMessage();
        return message != null ? message : "is invalid";
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
