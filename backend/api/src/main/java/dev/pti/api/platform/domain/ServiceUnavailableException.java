package dev.pti.api.platform.domain;

/** The API cannot answer right now but will be able to (503 {@code service-unavailable}, with {@code Retry-After}). */
public class ServiceUnavailableException extends ApiException {

    private static final long serialVersionUID = 1L;

    /** {@code Retry-After} of "no active feed yet" (DOC-31 §10.2). */
    public static final int NO_ACTIVE_FEED_RETRY_SECONDS = 30;

    private final int retryAfterSeconds;

    public ServiceUnavailableException(String detail, int retryAfterSeconds) {
        super(detail);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** The system has just started and no GTFS feed is ACTIVE yet (DOC-31 §10.2). */
    public static ServiceUnavailableException noActiveFeed() {
        return new ServiceUnavailableException("No active GTFS feed yet.", NO_ACTIVE_FEED_RETRY_SECONDS);
    }

    @Override
    public ProblemType type() {
        return ProblemType.SERVICE_UNAVAILABLE;
    }

    @Override
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
