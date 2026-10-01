package dev.pti.api.etlops.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Links into Grafana Explore for a batch id (DOC-32 E-32, E-37, DOC-28): the trace of the batch in Tempo and its log
 * lines in Loki, within the time the batch ran plus a minute on each side.
 */
public final class GrafanaLinks {

    /** The two links of a batch. */
    public record Links(String trace, String logs) {}

    private static final Duration MARGIN = Duration.ofMinutes(1);

    private final String baseUrl;

    /** @param baseUrl {@code pti.api.links.grafana-url}, for example {@code http://localhost:3000} */
    public GrafanaLinks(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * @param endedAt when the batch ended; {@code null} for one still running, which is read up to the start plus an
     *     hour
     */
    public Links forBatch(String batchId, Instant startedAt, @Nullable Instant endedAt) {
        Instant from = startedAt.minus(MARGIN);
        Instant to = (endedAt != null ? endedAt : startedAt.plus(Duration.ofHours(1))).plus(MARGIN);
        String trace = explore("tempo", "traceql", "query", "{ span.pti.batch_id = \"" + batchId + "\" }", from, to);
        String logs = explore(
                "loki", "range", "expr", "{service_name=~\"etl.*\"} | batch_id = \"" + batchId + "\"", from, to);
        return new Links(trace, logs);
    }

    private String explore(
            String datasource, String queryType, String queryField, String query, Instant from, Instant to) {
        String left = "{\"datasource\":\"" + datasource + "\",\"queries\":[{\"refId\":\"A\",\"queryType\":\""
                + queryType + "\",\"" + queryField + "\":\""
                + query.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}],\"range\":{\"from\":\"" + from.toEpochMilli() + "\",\"to\":\"" + to.toEpochMilli() + "\"}}";
        return baseUrl + "/explore?left=" + URLEncoder.encode(left, StandardCharsets.UTF_8);
    }
}
