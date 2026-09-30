package dev.pti.api.platform.adapter.out.jdbc;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;

/**
 * Times a query into {@code pti_api_db_query_seconds{datasource, query}} (DOC-31 §15). {@code query} is the name of
 * the SQL file, never the statement or a parameter.
 */
public final class QueryMetrics {

    static final String TIMER = "pti.api.db.query";

    private final MeterRegistry registry;

    public QueryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param datasource {@code reader} or {@code operator}
     * @param query {@code <group>/<name>} of the SQL file
     */
    public <T> T time(String datasource, String query, Supplier<T> call) {
        Timer timer = Timer.builder(TIMER)
                .description("Duration of a database query of the API")
                .tag("datasource", datasource)
                .tag("query", query)
                .register(registry);
        return timer.record(call);
    }
}
