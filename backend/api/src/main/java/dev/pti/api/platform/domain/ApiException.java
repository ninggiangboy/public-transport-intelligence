package dev.pti.api.platform.domain;

import dev.pti.common.error.PtiException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An error the API reports to its caller as Problem Details (DOC-30 §3). The message is the {@code detail} member: it
 * is English, specific to the occurrence and never carries SQL, class names or another user's data. Subclasses name
 * the {@link ProblemType} and may add field errors, extension members and a {@code Retry-After}.
 *
 * <p>No stack trace: these are answers to a caller's request, not bugs, and the traffic can be heavy.
 */
public abstract class ApiException extends PtiException {

    private static final long serialVersionUID = 1L;

    protected ApiException(String detail) {
        super(detail, null, false);
    }

    protected ApiException(String detail, @Nullable Throwable cause) {
        super(detail, cause, false);
    }

    public abstract ProblemType type();

    /** Errors per field ({@code errors} member); the field is the request's camelCase name or a JSON Pointer. */
    public List<FieldError> errors() {
        return List.of();
    }

    /** Extension members such as {@code existingReplayId} or {@code currentStatus}. */
    public Map<String, Object> extensions() {
        return Map.of();
    }

    /** Seconds for the {@code Retry-After} header, or {@code null} when there is none. */
    public @Nullable Integer retryAfterSeconds() {
        return null;
    }

    /** One entry of the {@code errors} member. */
    public record FieldError(String field, String message) {}
}
