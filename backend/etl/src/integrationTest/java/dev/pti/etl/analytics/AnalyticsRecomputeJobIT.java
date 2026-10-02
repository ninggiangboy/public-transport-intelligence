package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.gtfs.FeedBuilder;
import dev.pti.etl.gtfs.FetchFeedTasklet;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * {@code AnalyticsRecomputeJob} started by {@code job_request} as {@code POST /etl/jobs} and {@code make job-run} do
 * (DOC-23 §11.5, §18.7 AN-R-11): the request checks, and an OTP recompute over hand-made {@code fact_trip_update}
 * rows, the detector whose result is easiest to compute by hand. Bunching and disruption have their own recompute
 * tests in {@code analytics}; this one is about the job around them.
 */
class AnalyticsRecomputeJobIT extends BatchContextSupport {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static final AtomicBoolean FEED_LOADED = new AtomicBoolean();

    /** The api writes job requests as {@code replay_operator} (DOC-17). */
    private static JdbcTemplate api;

    @Autowired
    PtiJobLauncher launcher;

    @Autowired
    AnalyticsReferenceCache reference;

    @Autowired
    BusinessClock clock;

    private final String route = "ANRJ-" + UUID.randomUUID().toString().substring(0, 8);
    private final String goneRoute = "ANRK-" + UUID.randomUUID().toString().substring(0, 8);
    private TripUpdateRows facts;

    @BeforeAll
    static void openApi() {
        api = new JdbcTemplate(new SingleConnectionDataSource(
                MigratedDatabases.jdbcUrl("pti_warehouse"),
                "replay_operator",
                MigratedDatabases.password("replay_operator"),
                true));
    }

    @BeforeEach
    void prepare() throws Exception {
        facts = new TripUpdateRows(jdbc, route);
        if (FEED_LOADED.compareAndSet(false, true)) {
            Path zip = FeedBuilder.mini().write(FEEDS);
            JobExecution feed = launcher.start(
                    PtiJob.GTFS_STATIC_LOAD,
                    JobParams.identity(PtiJob.GTFS_STATIC_LOAD, "manual:" + UUID.randomUUID())
                            .addString(FetchFeedTasklet.SOURCE_URI, zip.toUri().toString(), false)
                            .addString(FetchFeedTasklet.ALLOW_REACTIVATE, "false", false)
                            .toJobParameters());
            await().atMost(Duration.ofSeconds(60)).until(() -> !feed.isRunning());
            assertThat(feed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }
        await().atMost(Duration.ofSeconds(30)).until(reference::hasActiveFeed);
    }

    @AfterEach
    void cleanUp() {
        facts.clear();
        jdbc.update("DELETE FROM insight.insight_otp_scorecard WHERE route_id IN (?, ?)", route, goneRoute);
    }

    private LocalDate today() {
        return clock.instant().atZone(CHICAGO).toLocalDate();
    }

    private UUID jobRequest(String parameters) {
        UUID id = UUID.randomUUID();
        api.update("""
                INSERT INTO ops.job_request (id, kind, job_name, job_parameters, requested_by)
                VALUES (?, 'RUN', 'AnalyticsRecomputeJob', ?::jsonb, 'user:test')""", id, parameters);
        return id;
    }

    private Map<String, Object> awaitRequest(UUID id) {
        await().atMost(Duration.ofSeconds(60))
                .until(() -> List.of("DONE", "FAILED", "REJECTED")
                        .contains(jdbc.queryForObject(
                                "SELECT status FROM ops.job_request WHERE id = ?", String.class, id)));
        return jdbc.queryForMap("SELECT status, message, job_execution_id FROM ops.job_request WHERE id = ?", id);
    }

    private List<LocalDate> otpDates(String routeId) {
        return jdbc
                .queryForList(
                        "SELECT service_date FROM insight.insight_otp_scorecard WHERE route_id = ? ORDER BY service_date",
                        Date.class,
                        routeId)
                .stream()
                .map(Date::toLocalDate)
                .toList();
    }

    @Test
    void anR11ARangeLongerThanSevenDaysIsRejectedBeforeAnythingRuns() {
        Instant to = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant from = to.minus(8, ChronoUnit.DAYS);

        Map<String, Object> request =
                awaitRequest(jobRequest("{\"fromTs\": \"" + from + "\", \"toTs\": \"" + to + "\"}"));

        assertThat(request.get("status")).isEqualTo("REJECTED");
        assertThat((String) request.get("message")).endsWith("is longer than 7 days");
        assertThat(request.get("job_execution_id")).isNull();
    }

    @Test
    void aRequestWithoutARangeOrWithAnUnknownDetectorIsRejected() {
        UUID noRange = jobRequest("{\"detectors\": \"OTP\"}");
        UUID unknown = jobRequest("{\"detectors\": \"OTP+WEATHER\", \"fromTs\": \"2026-09-29T00:00:00Z\","
                + " \"toTs\": \"2026-09-29T01:00:00Z\"}");

        assertThat(awaitRequest(noRange))
                .containsEntry("status", "REJECTED")
                .containsEntry("message", "Parameters fromTs and toTs are required for AnalyticsRecomputeJob");
        assertThat((String) awaitRequest(unknown).get("message"))
                .startsWith("Parameter detectors must be detectors joined by +");
    }

    @Test
    void anOtpRecomputeScoresEveryDayOfTheRangeAndRemovesARouteWithoutData() {
        LocalDate yesterday = today().minusDays(1);
        Instant noon = yesterday.atTime(12, 0).atZone(CHICAGO).toInstant();
        facts.arrival(yesterday, noon, 0).insert();
        facts.arrival(yesterday, noon.plusSeconds(60), 600).insert();
        jdbc.update("""
                INSERT INTO insight.insight_otp_scorecard (route_id, service_date, otp_percentage, on_time_count,
                  early_count, late_count, observation_count, trip_count, early_tolerance_seconds,
                  late_tolerance_seconds, computed_at, batch_id)
                VALUES (?, ?, 100.00, 1, 0, 0, 1, 1, 60, 300, now(), ?)""", goneRoute, Date.valueOf(yesterday), UUID.randomUUID());
        Instant from = yesterday.atTime(1, 0).atZone(CHICAGO).toInstant();
        Instant to = clock.instant().truncatedTo(ChronoUnit.MINUTES);

        Map<String, Object> request = awaitRequest(
                jobRequest("{\"detectors\": \"OTP\", \"fromTs\": \"" + from + "\", \"toTs\": \"" + to + "\"}"));

        assertThat(request).as(request::toString).containsEntry("status", "DONE");
        assertThat((String) request.get("message"))
                .as("the day before the range and the range up to yesterday (DOC-23 §11.1)")
                .startsWith("COMPLETED: Recomputed 2 items; OTP: 2 scopes");
        assertThat(otpDates(route)).containsExactly(yesterday);
        assertThat(otpDates(goneRoute)).as("not reproduced, so deleted").isEmpty();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM ops.etl_batch_step s
                        JOIN insight.insight_otp_scorecard o ON o.batch_id = s.batch_id
                        WHERE s.job_execution_id = ? AND s.step_name = 'recompute' AND o.route_id = ?""", Long.class, request.get("job_execution_id"), route))
                .as("the rows carry the batch_id of the step (DR-63)")
                .isEqualTo(1L);
    }

    @Test
    void theRecomputeJobIsInTheCatalogAndDeployed() {
        assertThat(launcher.job(PtiJob.ANALYTICS_RECOMPUTE)).isPresent();
        assertThat(PtiJob.byName("AnalyticsRecomputeJob")).contains(PtiJob.ANALYTICS_RECOMPUTE);
        assertThat(PtiJob.ANALYTICS_RECOMPUTE.identity().parameter()).isEqualTo("runKey");
    }
}
