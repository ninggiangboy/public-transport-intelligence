package dev.pti.analytics.alert.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.alert.domain.AlertDraft;
import dev.pti.analytics.alert.domain.AlertRecord;
import dev.pti.analytics.alert.domain.AlertTitles;
import dev.pti.analytics.alert.domain.AlertType;
import dev.pti.analytics.alert.domain.AnomalyTrigger;
import dev.pti.common.events.Audience;
import dev.pti.db.MigratedDatabases;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The three statements of DOC-23 §10.2 against the migrated schema, as {@code etl_writer}. */
class JdbcAlertWriterIT {

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcAlertWriter writer = new JdbcAlertWriter(db.jdbc);
    private final UUID episode = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        db.jdbc
                .sql("DELETE FROM ops.alert_event WHERE ref_id = :ref")
                .param("ref", episode.toString())
                .update();
    }

    private AlertDraft draft(AlertType type, int severity, Map<String, Object> body) {
        return AlertDraft.of(type, episode, severity, "AN1-R", AlertTitles.disruption("AN1-R", "Northbound"), body);
    }

    private long rows() {
        return db.jdbc
                .sql("SELECT count(*) FROM ops.alert_event WHERE ref_id = :ref")
                .param("ref", episode.toString())
                .query(Long.class)
                .single();
    }

    @Test
    void openInsertsTheAlertAndReturnsTheRow() {
        AlertDraft draft = draft(AlertType.DISRUPTION, 1, Map.of("disruptionId", episode.toString(), "zScore", 3.2));

        Optional<AlertRecord> opened = writer.open(draft);

        assertThat(opened).hasValueSatisfying(alert -> {
            assertThat(alert.id()).isEqualTo(draft.id());
            assertThat(alert.type()).isEqualTo(AlertType.DISRUPTION);
            assertThat(alert.severity()).isEqualTo(1);
            assertThat(alert.audience()).isEqualTo(Audience.PUBLIC);
            assertThat(alert.routeId()).isEqualTo("AN1-R");
            assertThat(alert.refTable()).isEqualTo("insight.insight_service_disruption");
            assertThat(alert.refId()).isEqualTo(episode.toString());
            assertThat(alert.title()).isEqualTo("Delays on route AN1-R Northbound");
            assertThat(alert.body())
                    .containsEntry("disruptionId", episode.toString())
                    .containsEntry("zScore", 3.2);
            assertThat(alert.createdAt())
                    .isBetween(
                            Instant.now().minus(Duration.ofMinutes(1)),
                            Instant.now().plusSeconds(5));
            assertThat(alert.resolvedAt()).isNull();
        });
    }

    @Test
    void runningTheSameInsertAgainChangesNothingAndReturnsNothing() {
        AlertDraft draft = draft(AlertType.BUNCHING, 1, Map.of("bunchingId", episode.toString()));

        assertThat(writer.open(draft)).isPresent();
        assertThat(writer.open(draft))
                .as("a replay finds the alert by its dedup key")
                .isEmpty();

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void anAlertWithoutARouteIsStored() {
        AlertDraft draft = AlertDraft.of(
                AlertType.TICKETING_ANOMALY,
                episode,
                1,
                null,
                AlertTitles.ticketingAnomaly(AnomalyTrigger.VOLUME, null, "KIOSK-AN1"),
                Map.of());

        assertThat(writer.open(draft))
                .hasValueSatisfying(alert -> assertThat(alert.routeId()).isNull());
    }

    @Test
    void resolveMarksTheAlertAndKeepsTheKeysThatWereThereBefore() {
        AlertDraft draft = draft(AlertType.BUNCHING, 1, Map.of("bunchingId", episode.toString()));
        writer.open(draft);
        // Triage adds its own keys to the body; closing the episode must not remove them.
        db.jdbc
                .sql("UPDATE ops.alert_event SET body = body || '{\"likelyCause\": \"traffic\"}'::jsonb WHERE id = :id")
                .param("id", draft.id())
                .update();

        Optional<AlertRecord> resolved =
                writer.resolve(draft.dedupKey(), Map.of("closeReason", "GAP_RECOVERED", "minGapSeconds", 95));

        assertThat(resolved).hasValueSatisfying(alert -> {
            assertThat(alert.resolvedAt()).isNotNull();
            assertThat(alert.body())
                    .containsEntry("bunchingId", episode.toString())
                    .containsEntry("likelyCause", "traffic")
                    .containsEntry("closeReason", "GAP_RECOVERED")
                    .containsEntry("minGapSeconds", 95);
            assertThat(alert.audience()).isEqualTo(Audience.OPERATIONS);
        });
    }

    @Test
    void resolveReturnsTheAudienceTheRowHasNow() throws SQLException {
        AlertDraft draft = draft(AlertType.DISRUPTION, 1, Map.of());
        writer.open(draft);
        // FR-09.5: triage narrowed the alert to ENGINEERING. etl_writer may not change the audience, the owner may.
        try (Connection owner = MigratedDatabases.connect("pti_warehouse", "pti_owner");
                Statement statement = owner.createStatement()) {
            statement.execute("UPDATE ops.alert_event SET audience = 'ENGINEERING' WHERE id = '" + draft.id() + "'");
        }

        assertThat(writer.resolve(draft.dedupKey(), Map.of()))
                .hasValueSatisfying(alert -> assertThat(alert.audience()).isEqualTo(Audience.ENGINEERING));
    }

    @Test
    void resolveTwiceReturnsNothingTheSecondTime() {
        AlertDraft draft = draft(AlertType.BUNCHING, 1, Map.of());
        writer.open(draft);

        assertThat(writer.resolve(draft.dedupKey(), Map.of())).isPresent();
        assertThat(writer.resolve(draft.dedupKey(), Map.of("again", true))).isEmpty();
    }

    @Test
    void resolveOfAnUnknownKeyReturnsNothing() {
        assertThat(writer.resolve("bunching:" + UUID.randomUUID(), Map.of())).isEmpty();
    }

    @Test
    void raiseSeverityGoesFromOneToTwoOnce() {
        AlertDraft draft = draft(AlertType.DISRUPTION, 1, Map.of("disruptionId", episode.toString()));
        writer.open(draft);

        Optional<AlertRecord> raised = writer.raiseSeverity(draft.dedupKey(), Map.of("peakZScore", 4.44));

        assertThat(raised).hasValueSatisfying(alert -> {
            assertThat(alert.severity()).isEqualTo(2);
            assertThat(alert.body())
                    .containsEntry("peakZScore", 4.44)
                    .containsEntry("disruptionId", episode.toString());
        });
        assertThat(writer.raiseSeverity(draft.dedupKey(), Map.of()))
                .as("already at 2")
                .isEmpty();
    }

    @Test
    void raiseSeverityIgnoresAResolvedAlert() {
        AlertDraft draft = draft(AlertType.DISRUPTION, 1, Map.of());
        writer.open(draft);
        writer.resolve(draft.dedupKey(), Map.of());

        assertThat(writer.raiseSeverity(draft.dedupKey(), Map.of())).isEmpty();
    }

    @Test
    void anAlertOpenedAtSeverityTwoIsNotRaisedAgain() {
        AlertDraft draft = draft(AlertType.DISRUPTION, 2, Map.of());
        writer.open(draft);

        assertThat(writer.raiseSeverity(draft.dedupKey(), Map.of())).isEmpty();
    }
}
