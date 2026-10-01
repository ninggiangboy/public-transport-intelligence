package dev.pti.api.etlops.application;

/**
 * What a request that creates a replay or a job request returns: the request, and whether it is the answer to a
 * repeated {@code Idempotency-Key}, in which case nothing was written (DOC-31 §8).
 */
public record Submitted<T>(T value, boolean idempotent) {

    public static <T> Submitted<T> created(T value) {
        return new Submitted<>(value, false);
    }

    public static <T> Submitted<T> repeated(T value) {
        return new Submitted<>(value, true);
    }
}
