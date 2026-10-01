package dev.pti.api.etlops.application;

import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.ApiException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Counts a write of an operator into {@code pti_api_write_requests_total} (DOC-31 §15): created, repeated or refused. */
final class Measured {

    private Measured() {}

    /**
     * Runs the write and records its outcome. An {@link ApiException} is a refusal ({@code rejected}) and is rethrown;
     * anything else is a fault, not a refusal, and is not counted here.
     */
    static <T> T write(WriteMetrics metrics, String operation, Function<T, String> outcome, Supplier<T> work) {
        T result;
        try {
            result = work.get();
        } catch (ApiException e) {
            metrics.recorded(operation, WriteMetrics.REJECTED);
            throw e;
        }
        metrics.recorded(operation, outcome.apply(result));
        return result;
    }

    static String of(Submitted<?> submitted) {
        return submitted.idempotent() ? WriteMetrics.IDEMPOTENT : WriteMetrics.CREATED;
    }
}
