package dev.pti.api.platform.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The Problem Details types of the API (DOC-30 §3.2). The slug is part of the contract with the frontend, which picks
 * its microcopy by it (DOC-35); adding one changes the contract, so the table in DOC-30, this enum and the microcopy
 * change together. {@code title} is fixed per type, {@code detail} is written per occurrence.
 */
public enum ProblemType {
    VALIDATION_ERROR("validation-error", 400, "Invalid request"),
    UNAUTHORIZED("unauthorized", 401, "Authentication required"),
    FORBIDDEN("forbidden", 403, "Access denied"),
    NOT_FOUND("not-found", 404, "Resource not found"),
    METHOD_NOT_ALLOWED("method-not-allowed", 405, "Method not allowed"),
    NOT_ACCEPTABLE("not-acceptable", 406, "Not acceptable"),
    CONFLICT("conflict", 409, "Conflict"),
    DLQ_INVALID_STATE("dlq-invalid-state", 409, "Dead letter in wrong state"),
    REPLAY_ALREADY_RUNNING("replay-already-running", 409, "Replay already running"),
    JOB_NOT_RESTARTABLE("job-not-restartable", 409, "Job cannot be restarted"),
    JOB_NOT_RUNNING("job-not-running", 409, "Job is not running"),
    PAYLOAD_TOO_LARGE("payload-too-large", 413, "Payload too large"),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", 415, "Unsupported media type"),
    INVALID_PAYLOAD("invalid-payload", 422, "Invalid payload"),
    PII_NOT_ALLOWED("pii-not-allowed", 422, "Personal data not allowed"),
    BUSINESS_KEY_CHANGED("business-key-changed", 422, "Business key cannot change"),
    REPLAY_WINDOW_INVALID("replay-window-invalid", 422, "Invalid replay window"),
    UNSUPPORTED_SOURCE("unsupported-source", 422, "Unsupported source"),
    ANALYTICS_RECOMPUTE_UNAVAILABLE("analytics-recompute-unavailable", 422, "Analytics recompute unavailable"),
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", 422, "Idempotency key reused"),
    JOB_NOT_ALLOWED("job-not-allowed", 422, "Job cannot be started manually"),
    INVALID_FLAG_VALUE("invalid-flag-value", 422, "Invalid flag value"),
    RATE_LIMITED("rate-limited", 429, "Too many requests"),
    INTERNAL_ERROR("internal-error", 500, "Internal error"),
    SIMULATOR_UNAVAILABLE("simulator-unavailable", 502, "Simulator unavailable"),
    SERVICE_UNAVAILABLE("service-unavailable", 503, "Service temporarily unavailable");

    private static final String URN_PREFIX = "urn:pti:problem:";

    private final String slug;
    private final int status;
    private final String title;

    ProblemType(String slug, int status, String title) {
        this.slug = slug;
        this.status = status;
        this.title = title;
    }

    /** The slug after {@code urn:pti:problem:}, for example {@code replay-already-running}. */
    public String slug() {
        return slug;
    }

    /** The {@code type} member of the Problem Details document. */
    public String urn() {
        return URN_PREFIX + slug;
    }

    /** The HTTP status that goes with the type. */
    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public static Optional<ProblemType> fromSlug(String slug) {
        return Arrays.stream(values()).filter(type -> type.slug.equals(slug)).findFirst();
    }
}
