package dev.pti.etl.batch;

/** A job-specific check of a {@code job_request} parameter, e.g. the allowed feed sources (DOC-21 §1.1). */
@FunctionalInterface
public interface JobParameterCheck {

    /** @throws IllegalArgumentException with the reason the value is refused */
    void check(PtiJob job, String name, String value);
}
