package dev.pti.api.insight.adapter.out.jdbc;

import dev.pti.api.insight.application.port.DispatchFeedbackStore;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.Feedback;
import dev.pti.api.platform.adapter.out.jdbc.OperatorRepository;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Operator feedback on dispatch suggestions (DOC-32 E-18), through the {@code operator} datasource: the read before
 * the write, and the write with the row it leaves. {@code replay_operator} may update only {@code operator_feedback},
 * {@code feedback_by} and {@code feedback_at} of the table (DOC-17).
 */
@Component
@OperatorRepository
public final class JdbcDispatchFeedbackStore implements DispatchFeedbackStore {

    private static final String GET = "insight/dispatch_get";
    private static final String FEEDBACK = "insight/dispatch_feedback";
    private static final String GET_SQL = SqlResources.read(GET);
    private static final String FEEDBACK_SQL = SqlResources.read(FEEDBACK);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;

    public JdbcDispatchFeedbackStore(@Qualifier("operator") JdbcClient jdbc, QueryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public Optional<DispatchSuggestion> find(UUID id) {
        return metrics.time(
                "operator",
                GET,
                () -> jdbc.sql(GET_SQL)
                        .param("id", id)
                        .query((rs, row) -> SuggestionRows.map(rs, ""))
                        .optional());
    }

    @Override
    public Optional<DispatchSuggestion> record(UUID id, Feedback feedback, String actor) {
        return metrics.time(
                "operator",
                FEEDBACK,
                () -> jdbc.sql(FEEDBACK_SQL)
                        .param("id", id)
                        .param("feedback", feedback.wireName())
                        .param("actor", actor)
                        .query((rs, row) -> SuggestionRows.map(rs, ""))
                        .optional());
    }
}
