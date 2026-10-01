package dev.pti.api.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DOC-17 for the endpoints of this slice, against the real grants: {@code api_reader} writes nothing, and {@code
 * replay_operator} writes only the acknowledgement and {@code resolved_at} of an alert, the rows Alertmanager sends,
 * and the feedback columns of a dispatch suggestion (DOC-32 E-18, E-21, E-80, DOC-31 §10.1).
 */
class InsightGrantsIT extends InsightIntegrationSupport {

    @Autowired
    @Qualifier("reader")
    private JdbcClient reader;

    @Autowired
    @Qualifier("operator")
    private JdbcClient operator;

    private void seed() {
        insertBunching(BUNCHING, "18", "2026-09-29T21:00:00Z", null, "OPEN");
        insertSuggestion(SUGGESTION, BUNCHING, "18", "2026-09-29T21:01:00Z", "0.820");
        insertAlert(
                "00000000-0000-0000-0000-0000000000a1",
                "BUNCHING",
                "OPERATIONS",
                "18",
                "2026-09-29T21:00:00Z",
                "bunching:x");
    }

    private static void assertDenied(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("The reader reads the insight and alert tables and writes none of them")
    void readerCannotWrite() {
        seed();

        assertThat(reader.sql("SELECT count(*) FROM insight.insight_bus_bunching")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(reader.sql("SELECT count(*) FROM ops.alert_event")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertDenied(() -> reader.sql("UPDATE ops.alert_event SET acknowledged_by = 'user:x', acknowledged_at = now()")
                .update());
        assertDenied(() -> reader.sql("UPDATE insight.insight_dispatch_suggestion SET operator_feedback = 'accepted',"
                        + " feedback_by = 'user:x', feedback_at = now()")
                .update());
        assertDenied(() -> reader.sql("DELETE FROM ops.alert_event").update());
        assertDenied(
                () -> reader.sql("DELETE FROM insight.insight_bus_bunching").update());
        assertDenied(() -> reader.sql("INSERT INTO ops.alert_event (id, type, severity, audience, title, dedup_key)"
                        + " VALUES (gen_random_uuid(), 'INFRA', 1, 'ENGINEERING', 't', 'k')")
                .update());
    }

    @Test
    @DisplayName("The operator changes the acknowledgement and resolved_at of an alert, and inserts alerts")
    void operatorWritesTheGrantedAlertColumns() {
        seed();

        assertThat(operator.sql("UPDATE ops.alert_event SET acknowledged_by = 'user:operator', acknowledged_at = now()")
                        .update())
                .isEqualTo(1);
        assertThat(operator.sql("UPDATE ops.alert_event SET resolved_at = now()")
                        .update())
                .isEqualTo(1);
        assertThat(operator.sql("INSERT INTO ops.alert_event (id, type, severity, audience, title, dedup_key)"
                                + " VALUES (gen_random_uuid(), 'INFRA', 1, 'ENGINEERING', 't', 'k')")
                        .update())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("The operator changes nothing else of an alert: not the title, body, audience, severity, nor a delete")
    void operatorCannotWriteOtherAlertColumns() {
        seed();

        assertDenied(() ->
                operator.sql("UPDATE ops.alert_event SET title = 'changed'").update());
        assertDenied(
                () -> operator.sql("UPDATE ops.alert_event SET body = '{}'").update());
        assertDenied(() ->
                operator.sql("UPDATE ops.alert_event SET audience = 'PUBLIC'").update());
        assertDenied(
                () -> operator.sql("UPDATE ops.alert_event SET severity = 2").update());
        assertDenied(() -> operator.sql("DELETE FROM ops.alert_event").update());
    }

    @Test
    @DisplayName("The operator records feedback on a suggestion and changes no other column of it")
    void operatorWritesOnlyTheFeedbackColumns() {
        seed();

        assertThat(operator.sql("UPDATE insight.insight_dispatch_suggestion SET operator_feedback = 'accepted',"
                                + " feedback_by = 'user:operator', feedback_at = now()")
                        .update())
                .isEqualTo(1);
        assertDenied(() -> operator.sql("UPDATE insight.insight_dispatch_suggestion SET action = 'no_action'")
                .update());
        assertDenied(() -> operator.sql("UPDATE insight.insight_dispatch_suggestion SET action_confidence = 1")
                .update());
        assertDenied(() -> operator.sql("UPDATE insight.insight_dispatch_suggestion SET state_snapshot = '{}'")
                .update());
        assertDenied(() ->
                operator.sql("DELETE FROM insight.insight_dispatch_suggestion").update());
        assertDenied(
                () -> operator.sql("INSERT INTO insight.insight_dispatch_suggestion (id, bunching_id, route_id, action,"
                                + " action_confidence, state_snapshot, model_version) VALUES (gen_random_uuid(),"
                                + " gen_random_uuid(), '18', 'no_action', 0.5, '{}', 'm')")
                        .update());
    }

    @Test
    @DisplayName("The operator reads no other insight table: bunching, disruption, OTP, ticketing are the reader's")
    void operatorReadsNoOtherInsightTable() {
        seed();

        assertDenied(() -> operator.sql("SELECT count(*) FROM insight.insight_bus_bunching")
                .query(Long.class)
                .single());
        assertDenied(() -> operator.sql("SELECT count(*) FROM insight.insight_service_disruption")
                .query(Long.class)
                .single());
        assertDenied(() -> operator.sql("UPDATE insight.insight_bus_bunching SET status = 'CLOSED'")
                .update());
    }
}
