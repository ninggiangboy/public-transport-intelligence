package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.platform.domain.ApiTime;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Writing the values of the responses of the ETL operations endpoints. */
final class EtlParams {

    private EtlParams() {}

    /** An instant as the API writes it ({@code ApiTime}), or {@code null} when there is none. */
    static @Nullable String instant(@Nullable Instant instant) {
        return instant != null ? ApiTime.format(instant) : null;
    }
}
