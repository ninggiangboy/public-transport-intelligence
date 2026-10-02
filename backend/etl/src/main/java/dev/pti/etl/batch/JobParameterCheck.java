package dev.pti.etl.batch;

import java.util.Map;

/** A job-specific check of a {@code job_request} parameter, e.g. the allowed feed sources (DOC-21 §1.1). */
@FunctionalInterface
public interface JobParameterCheck {

    /** @throws IllegalArgumentException with the reason the value is refused */
    void check(PtiJob job, String name, String value);

    /**
     * A check of the parameters of a request as a whole, once each of them has passed {@link #check}: a rule between
     * two parameters, or a parameter that must be present.
     *
     * @throws IllegalArgumentException with the reason the request is refused
     */
    default void checkAll(PtiJob job, Map<String, String> parameters) {}
}
