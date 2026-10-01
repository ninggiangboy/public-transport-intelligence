package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.DeadLetterReader;
import dev.pti.api.etlops.domain.ActionLogFilter;
import dev.pti.api.etlops.domain.ActionLogItem;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterFilter;
import dev.pti.api.etlops.domain.DeadLetterItem;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.api.etlops.domain.DeadLetterSummary;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors;
import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The dead letter queue and its action log (DOC-32 E-40…E-42, E-48), read as {@code api_reader}. */
@Component
public final class JdbcDeadLetterReader implements DeadLetterReader {

    private static final String LIST = "etlops/dlq_list";
    private static final String SUMMARY = "etlops/dlq_summary";
    private static final String CREATED_SINCE = "etlops/dlq_created_since";
    private static final String ACTIONS = "etlops/dlq_actions";
    private static final String LIST_SQL = SqlResources.read(LIST);
    private static final String SUMMARY_SQL = SqlResources.read(SUMMARY);
    private static final String CREATED_SINCE_SQL = SqlResources.read(CREATED_SINCE);
    private static final String ACTIONS_SQL = SqlResources.read(ACTIONS);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final EtlRows rows;
    private final DeadLetterRows details;

    public JdbcDeadLetterReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.rows = new EtlRows(mapper);
        this.details = new DeadLetterRows(jdbc, metrics, "reader", rows);
    }

    @Override
    public Page<DeadLetterItem> list(DeadLetterFilter filter, PageRequest page) {
        TimeIdCursors.TimeId after = TimeIdCursors.parse(page.after());
        List<DeadLetterItem> found = metrics.time(
                "reader",
                LIST,
                () -> jdbc.sql(LIST_SQL)
                        .param("statuses", filter.statuses().toArray(String[]::new))
                        .param("sources", filter.sources().toArray(String[]::new))
                        .param("stages", filter.stages().toArray(String[]::new))
                        .param("categories", filter.categories().toArray(String[]::new))
                        .param("severities", filter.severities().toArray(String[]::new))
                        .param("ruleIds", filter.ruleIds().toArray(String[]::new))
                        .param("from", ResultSets.nullableTimestamp(filter.from()))
                        .param("to", ResultSets.nullableTimestamp(filter.to()))
                        .param("cursorTs", after != null ? ResultSets.timestamp(after.time()) : null)
                        .param("cursorId", after != null ? after.id().toString() : null)
                        .param("limit", page.fetchSize())
                        .query((rs, row) -> DeadLetterRows.item(rs, true))
                        .list());
        return Page.of(
                page,
                found,
                item -> TimeIdCursors.of(item.createdAt(), item.id()).keys());
    }

    @Override
    public DeadLetterSummary summary(Instant now) {
        List<DeadLetterSummary.Group> groups = metrics.time(
                "reader",
                SUMMARY,
                () -> jdbc.sql(SUMMARY_SQL)
                        .query((rs, row) -> new DeadLetterSummary.Group(
                                DeadLetterStatus.valueOf(rs.getString("status")),
                                rs.getString("source"),
                                ResultSets.nullableInt(rs, "severity"),
                                rs.getLong("n")))
                        .list());
        long createdLastHour = metrics.time(
                "reader",
                CREATED_SINCE,
                () -> jdbc.sql(CREATED_SINCE_SQL)
                        .param("since", ResultSets.nullableTimestamp(now.minus(Duration.ofHours(1))))
                        .query(Long.class)
                        .single());
        return DeadLetterSummary.of(groups, createdLastHour);
    }

    @Override
    public Optional<DeadLetterDetail> find(UUID id) {
        return details.find(id);
    }

    @Override
    public Page<ActionLogItem> actions(ActionLogFilter filter, PageRequest page) {
        KeysetCursor after = page.after();
        Instant cursorTs = after == null ? null : EtlRows.instantKey(after, 0);
        Long cursorId = after == null ? null : EtlRows.longKey(after, 1);
        ActionLogFilter.ActorType type = filter.actorType();
        String actorType = type == null ? null : type.name().toLowerCase(Locale.ROOT);
        List<ActionLogItem> found = metrics.time(
                "reader",
                ACTIONS,
                () -> jdbc.sql(ACTIONS_SQL)
                        .param("from", ResultSets.nullableTimestamp(filter.from()))
                        .param("to", ResultSets.nullableTimestamp(filter.to()))
                        .param("actions", filter.actions().toArray(String[]::new))
                        .param("deadLetterId", Objects.toString(filter.deadLetterId(), null))
                        .param("actorType", actorType)
                        .param("cursorTs", ResultSets.nullableTimestamp(cursorTs))
                        .param("cursorId", cursorId)
                        .param("limit", page.fetchSize())
                        .query((rs, row) -> new ActionLogItem(
                                rs.getLong("id"),
                                rs.getObject("dead_letter_id", UUID.class),
                                rs.getString("action"),
                                rs.getString("actor"),
                                rs.getObject("confidence", BigDecimal.class),
                                rows.object(rs.getString("details")),
                                ResultSets.instant(rs, "at"),
                                rs.getString("source"),
                                DeadLetterStatus.valueOf(rs.getString("status"))))
                        .list());
        return Page.of(page, found, item -> EtlRows.keys(item.at(), item.id()));
    }
}
