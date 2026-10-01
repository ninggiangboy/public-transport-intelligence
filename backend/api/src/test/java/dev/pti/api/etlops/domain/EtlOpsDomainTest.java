package dev.pti.api.etlops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.sim.domain.SimulatorEndpoints;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules of the ETL operations that need no framework: the job catalog, the state table, ids and windows. */
class EtlOpsDomainTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:20:00Z");
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    @Test
    @DisplayName("E-33 the parameters are written the way the poller reads them: lists joined with +, sorted")
    void jobParameters() {
        JobCatalog.Submission otp = JobCatalog.validate(
                "OtpScorecardJob", Map.of("serviceDates", List.of("2026-09-28", "2026-09-27")), NOW, () -> CHICAGO);
        assertThat(otp.parameters()).containsEntry("serviceDates", "2026-09-27+2026-09-28");
        JobCatalog.Submission recompute = JobCatalog.validate(
                "AnalyticsRecomputeJob",
                Map.of(
                        "detectors",
                        List.of("TICKETING", "BUNCHING"),
                        "fromTs",
                        "2026-09-28T00:00:00Z",
                        "toTs",
                        "2026-09-29T00:00:00Z"),
                NOW,
                () -> CHICAGO);
        assertThat(recompute.parameters()).containsEntry("detectors", "BUNCHING+TICKETING");
    }

    @Test
    @DisplayName("E-33 refused: an unknown job, today, an hour off the hour, a range over 7 days, an unknown parameter")
    void jobRefusals() {
        assertThatThrownBy(() -> JobCatalog.validate("DedupRegistryCleanupJob", Map.of(), NOW, () -> CHICAGO))
                .isInstanceOf(JobNotAllowedException.class);
        assertThatThrownBy(() -> JobCatalog.validate(
                        "OtpScorecardJob", Map.of("serviceDates", List.of("2026-09-29")), NOW, () -> CHICAGO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> JobCatalog.validate(
                        "EtaAggregationJob", Map.of("hour", "2026-09-29T20:30:00Z"), NOW, () -> CHICAGO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> JobCatalog.validate(
                        "AnalyticsRecomputeJob",
                        Map.of("fromTs", "2026-09-01T00:00:00Z", "toTs", "2026-09-29T00:00:00Z"),
                        NOW,
                        () -> CHICAGO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> JobCatalog.validate("PartitionMaintenanceJob", Map.of("x", 1), NOW, () -> CHICAGO))
                .isInstanceOf(ValidationException.class);
        assertThat(JobCatalog.validate("GtfsStaticLoadJob", Map.of("sourceUri", "https://x/y.zip"), NOW, () -> CHICAGO)
                        .parameters())
                .containsEntry("sourceUri", "https://x/y.zip");
        assertThatThrownBy(() -> JobCatalog.validate(
                        "GtfsStaticLoadJob", Map.of("sourceUri", "ftp://x/y.zip"), NOW, () -> CHICAGO))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("DOC-32 §7 what each action may start from, and what a viewer is offered")
    void deadLetterActions() {
        assertThat(DeadLetterAction.allowedFor(DeadLetterStatus.PENDING_CONFIRM, true))
                .containsExactly("edit", "confirm", "discard");
        assertThat(DeadLetterAction.allowedFor(DeadLetterStatus.MANUAL, true))
                .containsExactly("edit", "replay", "discard", "resolve");
        assertThat(DeadLetterAction.allowedFor(DeadLetterStatus.REPLAYED, true)).isEmpty();
        assertThat(DeadLetterAction.allowedFor(DeadLetterStatus.MANUAL, false)).isEmpty();
        assertThat(DeadLetterStatus.REPLAY_REQUESTED.isOpen()).isTrue();
        assertThat(DeadLetterStatus.RESOLVED.isOpen()).isFalse();
    }

    @Test
    @DisplayName("Run ids: job:<n> and stream:<listener>:<minute> parse and print back; anything else is not a run")
    void runIds() {
        assertThat(RunId.parse("job:4127")).contains(RunId.job(4127));
        RunId stream =
                RunId.parse("stream:gtfs-rt-vehicle-position:2026-09-29T21:18Z").orElseThrow();
        assertThat(stream.minute()).isEqualTo(Instant.parse("2026-09-29T21:18:00Z"));
        assertThat(stream.text()).isEqualTo("stream:gtfs-rt-vehicle-position:2026-09-29T21:18Z");
        assertThat(RunId.parse("job:abc")).isEmpty();
        assertThat(RunId.parse("job:4127/../x")).isEmpty();
    }

    @Test
    @DisplayName("DOC-22 §4.1 the window rules: settle time, maximum age and length")
    void replayWindows() {
        RawReplayRules rules = new RawReplayRules(Duration.ofDays(7), Duration.ofMinutes(10), Duration.ofDays(29));
        assertThat(rules.problems(NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofHours(1)), NOW))
                .isEmpty();
        assertThat(rules.problems(NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(5)), NOW))
                .hasSize(1);
        assertThat(rules.problems(NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(39)), NOW))
                .hasSize(1);
        assertThat(rules.problems(NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofDays(1)), NOW))
                .hasSize(1);
        assertThat(rules.problems(NOW, NOW, NOW)).hasSize(1);
    }

    @Test
    @DisplayName("E-31 empty buckets are filled and the job statuses are folded into four counts")
    void summary() {
        Instant from = Instant.parse("2026-09-29T21:00:00Z");
        JobSummary.Point point = new JobSummary.Point(from.plusSeconds(300), 5, 1, 10, 9, 1, 0, 120);
        JobSummary summary = JobSummary.of(
                SummaryBucket.FIVE_MINUTES,
                from,
                from.plusSeconds(900),
                List.of(new JobSummary.Row("TICKETING_SALES", point)),
                Map.of("STARTED", 1, "STOPPING", 1, "COMPLETED", 3));
        assertThat(summary.stream()).hasSize(4);
        assertThat(summary.stream().get(2).points()).hasSize(3);
        assertThat(summary.stream().get(2).points().get(1)).isEqualTo(point);
        assertThat(summary.stream().get(0).points().get(1).batches()).isZero();
        assertThat(summary.batchJobs()).isEqualTo(new JobSummary.BatchJobCounts(2, 3, 0, 0));
    }

    @Test
    @DisplayName("E-57 the type of a flag is kept and the same number is the same value")
    void flags() {
        RuntimeFlag flag = new RuntimeFlag("a.b", 5, "d", "u", NOW);
        assertThat(flag.acceptsType(7)).isTrue();
        assertThat(flag.acceptsType("7")).isFalse();
        assertThat(flag.hasValue(5.0)).isTrue();
        assertThat(new RuntimeFlag("a.b", true, "d", "u", NOW).acceptsType(List.of()))
                .isFalse();
    }

    @Test
    @DisplayName("E-53 the estimate: coverage, a gap warning, an active replay warning and no history")
    void estimate() {
        Instant from = Instant.parse("2026-09-28T00:00:00Z");
        Instant to = from.plus(Duration.ofHours(1));
        ReplayEstimate full = ReplayEstimate.of(
                "TICKETING_SALES", from, to, new ReplayEstimate.History(180_000, 60, 60), 3000, false);
        assertThat(full.estimatedDurationSeconds()).isEqualTo(60);
        assertThat(full.warnings()).isEmpty();
        ReplayEstimate gaps =
                ReplayEstimate.of("TICKETING_SALES", from, to, new ReplayEstimate.History(1000, 10, 30), 3000, true);
        assertThat(gaps.warnings()).hasSize(2);
        assertThat(ReplayEstimate.of("TICKETING_SALES", from, to, new ReplayEstimate.History(0, 0, 0), 3000, false)
                        .basis())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("E-90 only the endpoints of the simulator's control API are forwarded; a Location is the API's own")
    void simulatorEndpoints() {
        assertThat(SimulatorEndpoints.allows("GET", "/sim/status")).isTrue();
        assertThat(SimulatorEndpoints.allows("POST", "/sim/scenarios/bad-data")).isTrue();
        assertThat(SimulatorEndpoints.allows("DELETE", "/sim/scenario-runs/0192f7b1-2c3d"))
                .isTrue();
        assertThat(SimulatorEndpoints.allows("POST", "/sim/status")).isFalse();
        assertThat(SimulatorEndpoints.allows("GET", "/actuator/health")).isFalse();
        assertThat(SimulatorEndpoints.publicLocation("/sim/scenario-runs/abc", "http://source-simulator:8080"))
                .isEqualTo("/api/v1/sim/scenario-runs/abc");
        assertThat(SimulatorEndpoints.publicLocation(
                        "http://source-simulator:8080/sim/scenario-runs/abc", "http://source-simulator:8080"))
                .isEqualTo("/api/v1/sim/scenario-runs/abc");
    }
}
