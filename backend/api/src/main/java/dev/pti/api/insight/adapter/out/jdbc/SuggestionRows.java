package dev.pti.api.insight.adapter.out.jdbc;

import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.Feedback;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Maps the columns of {@code insight_dispatch_suggestion}, with an optional prefix for a joined row. */
final class SuggestionRows {

    private SuggestionRows() {}

    static DispatchSuggestion map(ResultSet rs, String prefix) throws SQLException {
        String feedback = rs.getString(prefix + "operator_feedback");
        return new DispatchSuggestion(
                ResultSets.uuid(rs, prefix + "id"),
                ResultSets.uuid(rs, prefix + "bunching_id"),
                rs.getString(prefix + "route_id"),
                rs.getString(prefix + "action"),
                rs.getBigDecimal(prefix + "action_confidence"),
                rs.getString(prefix + "model_version"),
                ResultSets.instant(rs, prefix + "created_at"),
                rs.getString(prefix + "state_snapshot"),
                feedback == null
                        ? null
                        : Feedback.fromWireName(feedback)
                                .orElseThrow(() -> new IllegalStateException("Unknown operator_feedback " + feedback)),
                rs.getString(prefix + "feedback_by"),
                ResultSets.nullableInstant(rs, prefix + "feedback_at"));
    }

    /** The suggestion of a left-joined row, or {@code null} when the episode has none. */
    static @Nullable DispatchSuggestion mapJoined(ResultSet rs, String prefix) throws SQLException {
        return ResultSets.nullableUuid(rs, prefix + "id") == null ? null : map(rs, prefix);
    }
}
