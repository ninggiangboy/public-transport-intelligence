package dev.pti.api.system.adapter.out.jdbc;

import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.system.application.port.FreshnessQuery;
import dev.pti.api.system.domain.SourceReading;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The freshness probe's SQL (DOC-32 E-60), through the {@code reader} datasource. */
@Component
public final class JdbcFreshnessQuery implements FreshnessQuery {

    private static final String SOURCES = "system/freshness";
    private static final String ETA = "system/freshness-eta";
    private static final String SOURCES_SQL = SqlResources.read(SOURCES);
    private static final String ETA_SQL = SqlResources.read(ETA);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;

    public JdbcFreshnessQuery(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public SourceReading readSources() {
        return metrics.time(
                "reader",
                SOURCES,
                () -> jdbc.sql(SOURCES_SQL).query(JdbcFreshnessQuery::sources).single());
    }

    @Override
    public Optional<Instant> readEtaComputedAt() {
        return metrics.time(
                "reader",
                ETA,
                () -> jdbc.sql(ETA_SQL)
                        .query((rs, row) -> Optional.ofNullable(instant(rs, "eta_computed_at")))
                        .single());
    }

    private static SourceReading sources(ResultSet rs, int row) throws SQLException {
        return new SourceReading(
                instant(rs, "vp_last"),
                instant(rs, "tu_last"),
                instant(rs, "sales_last"),
                instant(rs, "otp_computed_at"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }
}
