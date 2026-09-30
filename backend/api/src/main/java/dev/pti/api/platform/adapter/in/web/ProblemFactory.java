package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.ProblemType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Builds the Problem Details document of every error the API returns (RFC 9457, DOC-30 §3.1): the advice for
 * exceptions of the controllers and the security and rate-limit filters, which answer before any controller runs, all
 * go through here, so the members, their order and {@code pti_api_problems_total} are the same everywhere.
 *
 * <p>The body is a plain ordered map. It never carries a stack trace, a class name, SQL or a parameter value.
 */
public final class ProblemFactory {

    static final String PROBLEM_JSON = "application/problem+json";

    private final MeterRegistry registry;

    public ProblemFactory(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param detail what went wrong this time; {@code null} for the standard sentence of the type
     * @param instance the request path, without the query string
     * @param extensions extra members such as {@code existingReplayId} or {@code retryAfterSeconds}
     */
    public Map<String, Object> body(
            ProblemType type,
            @Nullable String detail,
            @Nullable String instance,
            List<FieldError> errors,
            Map<String, Object> extensions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.urn());
        body.put("title", type.title());
        body.put("status", type.status());
        body.put("detail", detail != null ? detail : defaultDetail(type));
        if (instance != null) {
            body.put("instance", instance);
        }
        String traceId = MDC.get(ApiRequestFilter.TRACE_ID_KEY);
        if (traceId != null) {
            body.put("traceId", traceId);
        }
        if (!errors.isEmpty()) {
            List<Map<String, String>> entries = new ArrayList<>();
            for (FieldError error : errors) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("field", error.field());
                entry.put("message", error.message());
                entries.add(entry);
            }
            body.put("errors", entries);
        }
        body.putAll(extensions);
        registry.counter("pti.api.problems", "type", type.slug(), "status", String.valueOf(type.status()))
                .increment();
        return body;
    }

    static String defaultDetail(ProblemType type) {
        return switch (type) {
            case UNAUTHORIZED -> "Authentication is required to access this resource.";
            case FORBIDDEN -> "You do not have permission to access this resource.";
            case NOT_FOUND -> "The requested resource does not exist.";
            case METHOD_NOT_ALLOWED -> "The HTTP method is not supported for this resource.";
            case NOT_ACCEPTABLE -> "The API only produces application/json.";
            case UNSUPPORTED_MEDIA_TYPE -> "The request content type is not supported.";
            case PAYLOAD_TOO_LARGE -> "The request body is too large.";
            case VALIDATION_ERROR -> "The request is not valid.";
            case RATE_LIMITED -> "Too many requests. Try again later.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable. Try again shortly.";
            case INTERNAL_ERROR -> "An unexpected error occurred. Quote the trace id when reporting.";
            default -> type.title() + ".";
        };
    }
}
