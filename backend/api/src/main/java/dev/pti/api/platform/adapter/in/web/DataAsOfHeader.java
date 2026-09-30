package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ApiTime;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * The {@code X-Data-As-Of} header (DOC-31 §7.3): the event time of the newest data a response reflects. A use case
 * returns the instant ({@code WithAsOf}); the controller puts {@code DataAsOfHeader.of(result.asOf())} into its
 * {@code ResponseEntity}. A source that never had data has no as-of, and the header is left out.
 */
public final class DataAsOfHeader {

    public static final String NAME = "X-Data-As-Of";

    private DataAsOfHeader() {}

    /** Headers holding only {@code X-Data-As-Of}, or none when {@code asOf} is {@code null}. */
    public static HttpHeaders of(@Nullable Instant asOf) {
        HttpHeaders headers = new HttpHeaders();
        if (asOf != null) {
            headers.set(NAME, ApiTime.format(asOf));
        }
        return headers;
    }
}
