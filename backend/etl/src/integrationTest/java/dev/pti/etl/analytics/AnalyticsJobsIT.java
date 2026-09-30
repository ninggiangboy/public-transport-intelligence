package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.analytics.config.AnalyticsProperties;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.eta.adapter.out.jdbc.JdbcEtaAggregateStore;
import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.analytics.eta.application.port.EtaAggregateStore;
import dev.pti.analytics.eta.domain.EtaWindow;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.analytics.adapter.in.batch.EtaAggregationTasklet;
import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.batch.BatchSteps;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.gtfs.FeedBuilder;
import dev.pti.etl.gtfs.FetchFeedTasklet;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * {@code EtaAggregationJob} and {@code OtpScorecardJob} as {@code etl-batch} runs them (DOC-23 §4.3, §7.2, §8.2,
 * §18.4 AN-E-06, 08, 11, §18.5 AN-O-05, 06): started by launcher and by {@code job_request}, against hand-made
 * {@code fact_trip_update} rows whose results are computed by hand. The mini feed makes a feed ACTIVE, which the
 * analytics jobs need to run at all.
 */
class AnalyticsJobsIT extends BatchContextSupport {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    /** A Tuesday in the past; 28 days back from it is Tuesday 8 September. */
    private static final Instant HOUR = Instant.parse("2026-10-06T12:00:00Z");

    private static final LocalDate TUESDAY = LocalDate.parse("2026-09-29");

    private static final AtomicBoolean FEED_LOADED = new AtomicBoolean();

    @Autowired
    PtiJobLauncher launcher;

    @Autowired
    BatchSteps steps;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    AnalyticsReferenceCache reference;

    @Autowired
    BusinessClock clock;

    @Autowired
    AdvisoryLock lock;

    @Autowired
    TransactionLimits limits;

    @Autowired
    TransactionRunner tx;

    @Autowired
    AnalyticsMetrics metrics;

    @Autowired
    AnalyticsProperties properties;

    @Autowired
    JdbcClient jdbcClient;

    private final String route = "AN5J-" + UUID.randomUUID().toString().substring(0, 8);
    private final String otherRoute = "AN5K-" + UUID.randomUUID().toString().substring(0, 8);
    private TripUpdateRows facts;
    private TripUpdateRows otherFacts;

    /** The api writes job requests as {@code replay_operator} (DOC-17); {@code etl_writer} cannot insert them. */
    private static JdbcTemplate api;

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
        otherFacts = new TripUpdateRows(jdbc, otherRoute);
        jdbc.update("DELETE FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'");
        if (FEED_LOADED.compareAndSet(false, true)) {
            Path zip = FeedBuilder.mini().write(FEEDS);
            JobExecution feed = awaitEnd(launcher.start(
                    PtiJob.GTFS_STATIC_LOAD,
                    JobParams.identity(PtiJob.GTFS_STATIC_LOAD, "manual:" + UUID.randomUUID())
                            .addString(FetchFeedTasklet.SOURCE_URI, zip.toUri().toString(), false)
                            .addString(FetchFeedTasklet.ALLOW_REACTIVATE, "false", false)
                            .toJobParameters()));
            assertThat(feed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }
        await().atMost(Duration.ofSeconds(30)).until(reference::hasActiveFeed);
    }

    @AfterEach
    void cleanUp() {
        facts.clear();
        otherFacts.clear();
        for (String r : List.of(route, otherRoute)) {
            jdbc.update("DELETE FROM insight.insight_eta_prediction WHERE route_id = ?", r);
            jdbc.update("DELETE FROM insight.insight_otp_scorecard WHERE route_id = ?", r);
        }
        jdbc.update("DELETE FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private JobExecution runEta(String runKey, boolean force) {
        return runEta(runKey, force, HOUR);
    }

    private JobExecution runEta(String runKey, boolean force, Instant hour) {
        JobParameters parameters = JobParams.identity(PtiJob.ETA_AGGREGATION, runKey)
                .addString(EtaAggregationTasklet.HOUR, hour.toString(), false)
                .addString(EtaAggregationTasklet.FORCE, Boolean.toString(force), false)
                .toJobParameters();
        return awaitEnd(launcher.launchIfNew(PtiJob.ETA_AGGREGATION, parameters).orElseThrow());
    }

    /** A stale row keeps the route in the list of routes that the aggregation visits. */
    private void listRoute(String routeId) {
        jdbc.update("""
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                VALUES (?, 'GONE', 1, 1, 1.0, 1, 1, 1, DATE '2026-09-01', DATE '2026-09-29',
                        TIMESTAMPTZ '2026-09-29 12:00:00Z', ?)""", routeId, UUID.randomUUID());
    }

    private void arrivals(TripUpdateRows rows, String stop, int... delays) {
        Instant scheduled = Instant.parse("2026-09-29T22:10:00Z");
        for (int i = 0; i < delays.length; i++) {
            rows.arrival(TUESDAY, scheduled.plusSeconds(600L * i), delays[i])
                    .stop(stop)
                    .insert();
        }
    }

    private List<Map<String, Object>> etaRows(String routeId) {
        return jdbc.queryForList("""
                SELECT stop_id, day_of_week, hour_of_day, avg_delay_seconds, median_delay_seconds, p90_delay_seconds,
                       sample_count, window_start, window_end, computed_at
                FROM insight.insight_eta_prediction WHERE route_id = ? ORDER BY stop_id, day_of_week, hour_of_day""", routeId);
    }

    private String exitDescription(JobExecution execution) {
        return execution.getStepExecutions().iterator().next().getExitStatus().getExitDescription();
    }

    // ------------------------------------------------------------------------------------------ ETA

    @Test
    void theEtaJobAggregatesEveryRouteAndStoresTheCheckpoint() {
        listRoute(route);
        arrivals(facts, "S1", 60, 120, 180, 240, 300);

        JobExecution execution = runEta("manual:" + UUID.randomUUID(), false);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(etaRows(route)).hasSize(1);
        Map<String, Object> row = etaRows(route).getFirst();
        assertThat(row.get("stop_id")).isEqualTo("S1");
        assertThat((BigDecimal) row.get("avg_delay_seconds")).isEqualByComparingTo("180.0");
        assertThat(row.get("median_delay_seconds")).isEqualTo(180);
        assertThat(row.get("p90_delay_seconds")).isEqualTo(300);
        assertThat(row.get("sample_count")).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                        "SELECT job_execution_id FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'",
                        Long.class))
                .isEqualTo(execution.getId());
        assertThat(exitDescription(execution))
                .contains("for 2026-10-06T12:00:00Z")
                .contains("upserted");
        assertThat(execution.getStepExecutions().iterator().next().getStepName())
                .isEqualTo("aggregateEta");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM ops.etl_batch_step
                        WHERE job_execution_id = ? AND step_name = 'aggregateEta'
                          AND batch_id = (SELECT batch_id FROM insight.insight_eta_prediction WHERE route_id = ?)""", Long.class, execution.getId(), route))
                .as("the rows carry the batch_id of the step (DR-63)")
                .isEqualTo(1L);
    }

    @Test
    void anE08AnUnchangedWatermarkEndsTheRunWithExitNoopAndWritesNothing() {
        listRoute(route);
        arrivals(facts, "S1", 60, 120);
        runEta("manual:" + UUID.randomUUID(), false);
        List<Map<String, Object>> before = etaRows(route);
        Object batch = jdbc.queryForObject(
                "SELECT batch_id FROM insight.insight_eta_prediction WHERE route_id = ?", Object.class, route);

        JobExecution again = runEta("manual:" + UUID.randomUUID(), false);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(again.getExitStatus().getExitCode()).isEqualTo("NOOP");
        assertThat(etaRows(route)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT batch_id FROM insight.insight_eta_prediction WHERE route_id = ?", Object.class, route))
                .isEqualTo(batch);
    }

    @Test
    void aForcedRunWritesEvenWhenNothingIsNew() {
        listRoute(route);
        arrivals(facts, "S1", 60, 120);
        runEta("manual:" + UUID.randomUUID(), false);

        JobExecution forced = runEta("manual:" + UUID.randomUUID(), true);

        assertThat(forced.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
    }

    @Test
    void anE06RunningTheSameHourAgainGivesTheSameRowsAndAManualKeyIsAllowedAfterACompleteInstance() {
        listRoute(route);
        arrivals(facts, "S1", 60, 120, 180, 240, 300);
        arrivals(facts, "S2", 10, 20, 400);
        JobExecution first = runEta("manual:" + UUID.randomUUID(), true);
        List<Map<String, Object>> before = etaRows(route);
        Object firstBatch = jdbc.queryForObject(
                "SELECT DISTINCT batch_id FROM insight.insight_eta_prediction WHERE route_id = ?", Object.class, route);

        JobExecution second = runEta("manual:" + UUID.randomUUID(), true);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(etaRows(route))
                .as("the same rows, including computed_at = H")
                .isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT DISTINCT batch_id FROM insight.insight_eta_prediction WHERE route_id = ?",
                        Object.class,
                        route))
                .as("only the batch moves")
                .isNotEqualTo(firstBatch);
    }

    @Test
    void theScheduledKeyOfAnHourRunsOnceEvenIfTheCronFiresTwice() {
        String key = "scheduled:" + HOUR;
        listRoute(route);
        arrivals(facts, "S1", 60);
        runEta(key, true);

        JobParameters same = JobParams.identity(PtiJob.ETA_AGGREGATION, key)
                .addString(EtaAggregationTasklet.HOUR, HOUR.toString(), false)
                .toJobParameters();

        assertThat(launcher.launchIfNew(PtiJob.ETA_AGGREGATION, same)).isEmpty();
    }

    /** The store of the aggregation with a failure on one route: the pod "dies" once while aggregating it. */
    private static final class FlakyStore implements EtaAggregateStore {

        private final EtaAggregateStore real;
        private final List<String> routes;
        private final String failOn;
        private boolean failed;
        final List<String> recomputed = new ArrayList<>();

        FlakyStore(EtaAggregateStore real, List<String> routes, String failOn) {
            this.real = real;
            this.routes = routes;
            this.failOn = failOn;
        }

        @Override
        public List<String> routeIds() {
            return routes;
        }

        @Override
        public String sourceWatermark(DateRange serviceDates) {
            return real.sourceWatermark(serviceDates);
        }

        @Override
        public Optional<String> checkpoint() {
            return real.checkpoint();
        }

        @Override
        public void saveCheckpoint(String watermark, Instant watermarkTs, @Nullable Long jobExecutionId) {
            real.saveCheckpoint(watermark, watermarkTs, jobExecutionId);
        }

        @Override
        public Merge recompute(String routeId, EtaWindow window, Instant computedAt, UUID batchId) {
            recomputed.add(routeId);
            if (routeId.equals(failOn) && !failed) {
                failed = true;
                throw new IllegalStateException("Connection lost while aggregating " + routeId);
            }
            return real.recompute(routeId, window, computedAt, batchId);
        }
    }

    @Test
    void anE11ARestartContinuesAtTheRouteThatFailedAndEndsWithTheResultOfOneRun() throws Exception {
        arrivals(facts, "S1", 60, 120);
        arrivals(otherFacts, "S1", 200, 400);
        List<String> routes = List.of(route, otherRoute);
        FlakyStore store = new FlakyStore(new JdbcEtaAggregateStore(jdbcClient), routes, otherRoute);
        EtaAggregator aggregator = new EtaAggregator(
                store,
                reference,
                lock,
                limits,
                tx,
                new RunReporter(metrics),
                properties.eta().toSettings());
        Job job = steps.job("TestEtaRestartJob")
                .start(steps.tasklet(
                        "aggregateEta",
                        new EtaAggregationTasklet(new EtaRunPlanner(store, reference, clock), aggregator, clock)))
                .build();
        JobParameters parameters = JobParams.identity(PtiJob.ETA_AGGREGATION, "manual:" + UUID.randomUUID())
                .addString(EtaAggregationTasklet.HOUR, HOUR.toString(), false)
                .toJobParameters();

        JobExecution failed = awaitEnd(jobOperator.start(job, parameters));

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(etaRows(route)).as("the first route's call committed").hasSize(1);
        assertThat(etaRows(otherRoute)).isEmpty();
        assertThat(jdbc.queryForList("SELECT 1 FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'"))
                .as("the checkpoint belongs to the last route, which has not been aggregated")
                .isEmpty();
        assertThat(failed.getStepExecutions()
                        .iterator()
                        .next()
                        .getExecutionContext()
                        .getInt("pti.eta.index"))
                .isEqualTo(1);

        // Starting the job again with the same identifying parameters resumes the failed instance.
        JobExecution restarted = awaitEnd(jobOperator.start(job, parameters));

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(store.recomputed)
                .as("the first route is not done again")
                .containsExactly(route, otherRoute, otherRoute);
        assertThat((BigDecimal) etaRows(route).getFirst().get("avg_delay_seconds"))
                .isEqualByComparingTo("90.0");
        assertThat((BigDecimal) etaRows(otherRoute).getFirst().get("avg_delay_seconds"))
                .isEqualByComparingTo("300.0");
        assertThat(jdbc.queryForObject(
                        "SELECT job_execution_id FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'",
                        Long.class))
                .isEqualTo(restarted.getId());
        List<Object> batches = jdbc.queryForList(
                "SELECT batch_id FROM insight.insight_eta_prediction WHERE route_id IN (?, ?)",
                Object.class,
                route,
                otherRoute);
        assertThat(batches)
                .as("a restart is a new step execution with a new batch_id")
                .doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------------------------------ OTP

    private JobExecution runOtp(String runKey, String serviceDates) {
        var builder = JobParams.identity(PtiJob.OTP_SCORECARD, runKey);
        if (serviceDates != null) {
            builder.addString("serviceDates", serviceDates, false);
        }
        return awaitEnd(launcher.launchIfNew(PtiJob.OTP_SCORECARD, builder.toJobParameters())
                .orElseThrow());
    }

    private LocalDate today() {
        return clock.instant().atZone(CHICAGO).toLocalDate();
    }

    private void otpArrivals(TripUpdateRows rows, LocalDate day, int... delays) {
        Instant noon = day.atTime(12, 0).atZone(CHICAGO).toInstant();
        for (int i = 0; i < delays.length; i++) {
            rows.arrival(day, noon.plusSeconds(60L * i), delays[i]).insert();
        }
    }

    private Map<LocalDate, Map<String, Object>> otpRows(String routeId) {
        Map<LocalDate, Map<String, Object>> byDate = new LinkedHashMap<>();
        jdbc.queryForList(
                        "SELECT * FROM insight.insight_otp_scorecard WHERE route_id = ? ORDER BY service_date DESC",
                        routeId)
                .forEach(row -> byDate.put(((Date) row.get("service_date")).toLocalDate(), row));
        return byDate;
    }

    @Test
    void anO05TheScheduledRunOfADayScoresTheThreeDaysBeforeIt() {
        LocalDate runDate = today();
        otpArrivals(facts, runDate.minusDays(1), -301, -300, 0, 300, 301);
        otpArrivals(facts, runDate.minusDays(2), 0, 10, 400);
        otpArrivals(facts, runDate.minusDays(3), 0);
        otpArrivals(facts, runDate.minusDays(4), 0);

        JobExecution execution = runOtp("scheduled:" + runDate, null);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<LocalDate, Map<String, Object>> rows = otpRows(route);
        assertThat(rows.keySet())
                .as("yesterday and two days before, not the fourth day")
                .containsExactly(runDate.minusDays(1), runDate.minusDays(2), runDate.minusDays(3));
        assertThat((BigDecimal) rows.get(runDate.minusDays(1)).get("otp_percentage"))
                .isEqualByComparingTo("60.00");
        assertThat(rows.get(runDate.minusDays(1)).get("observation_count")).isEqualTo(5);
        assertThat((BigDecimal) rows.get(runDate.minusDays(2)).get("otp_percentage"))
                .isEqualByComparingTo("66.67");
        assertThat(exitDescription(execution)).startsWith("Scored 3 service dates");
    }

    @Test
    void theScheduledKeyOfADayRunsOnceAndAManualRunOfTheSameDaysIsAnotherInstance() {
        LocalDate runDate = today();
        otpArrivals(facts, runDate.minusDays(1), 0, 600);
        String key = "scheduled:" + runDate.minusDays(3650);
        JobExecution first = runOtp(key, "%s".formatted(runDate.minusDays(1)));

        assertThat(launcher.launchIfNew(PtiJob.OTP_SCORECARD, JobParams.runKey(PtiJob.OTP_SCORECARD, key)))
                .as("the same scheduled key is already complete")
                .isEmpty();
        JobExecution manual =
                runOtp("manual:" + UUID.randomUUID(), runDate.minusDays(1).toString());

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(manual.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(otpRows(route)).hasSize(1);
        assertThat((BigDecimal) otpRows(route).values().iterator().next().get("otp_percentage"))
                .isEqualByComparingTo("50.00");
    }

    @Test
    void anO04ARerunAfterAReplayAddsTheNewRouteAndRemovesTheOneWithoutData() {
        LocalDate day = today().minusDays(1);
        otpArrivals(facts, day, 0);
        otpArrivals(otherFacts, day, 0);
        runOtp("manual:" + UUID.randomUUID(), day.toString());
        assertThat(otpRows(route)).hasSize(1);
        assertThat(otpRows(otherRoute)).hasSize(1);

        otherFacts.clear();
        otpArrivals(facts, day, 0, 0);
        runOtp("manual:" + UUID.randomUUID(), day.toString());

        assertThat(otpRows(otherRoute)).isEmpty();
        assertThat(otpRows(route).get(day).get("observation_count")).isEqualTo(3);
    }

    // ------------------------------------------------------------------------------------------ job_request

    private UUID jobRequest(String jobName, String parameters) {
        UUID id = UUID.randomUUID();
        api.update("""
                INSERT INTO ops.job_request (id, kind, job_name, job_parameters, requested_by)
                VALUES (?, 'RUN', ?, ?::jsonb, 'user:test')""", id, jobName, parameters);
        return id;
    }

    private Map<String, Object> request(UUID id) {
        return jdbc.queryForMap("SELECT status, message, job_execution_id FROM ops.job_request WHERE id = ?", id);
    }

    private Map<String, Object> awaitRequest(UUID id) {
        await().atMost(Duration.ofSeconds(60))
                .until(() -> List.of("DONE", "FAILED", "REJECTED")
                        .contains((String) request(id).get("status")));
        return request(id);
    }

    @Test
    void anO06ARequestForAFutureServiceDateIsRejectedWithAnEnglishMessage() {
        String future = today().plusDays(2).toString();

        Map<String, Object> request =
                awaitRequest(jobRequest("OtpScorecardJob", "{\"serviceDates\": \"" + future + "\"}"));

        assertThat(request.get("status")).isEqualTo("REJECTED");
        assertThat(request.get("message"))
                .isEqualTo("Service date " + future + " is not before today (" + today() + ")");
        assertThat(request.get("job_execution_id")).isNull();
    }

    @Test
    void aRequestWithADateBeyondTheRetentionOfTripUpdatesIsRejected() {
        String ancient = today().minusDays(4000).toString();

        Map<String, Object> request =
                awaitRequest(jobRequest("OtpScorecardJob", "{\"serviceDates\": \"" + ancient + "\"}"));

        assertThat(request.get("status")).isEqualTo("REJECTED");
        assertThat((String) request.get("message")).contains("older than the retention of trip updates");
    }

    @Test
    void badEtaParametersAreRejectedBeforeAnythingRuns() {
        UUID future = jobRequest("EtaAggregationJob", "{\"hour\": \"2099-01-01T00:00:00Z\"}");
        UUID offHour = jobRequest("EtaAggregationJob", "{\"hour\": \"2026-10-06T12:30:00Z\"}");
        UUID notAnInstant = jobRequest("EtaAggregationJob", "{\"hour\": \"noon\"}");
        UUID force = jobRequest("EtaAggregationJob", "{\"force\": \"maybe\"}");
        UUID runKey = jobRequest("EtaAggregationJob", "{\"runKey\": \"scheduled:x\"}");

        assertThat((String) awaitRequest(future).get("message")).contains("is after the current hour");
        assertThat((String) awaitRequest(offHour).get("message"))
                .isEqualTo("Parameter hour must be on the hour: 2026-10-06T12:30:00Z");
        assertThat((String) awaitRequest(notAnInstant).get("message"))
                .isEqualTo("Parameter hour is not an ISO-8601 instant: noon");
        assertThat((String) awaitRequest(force).get("message"))
                .isEqualTo("Parameter force must be true or false: maybe");
        assertThat((String) awaitRequest(runKey).get("message"))
                .isEqualTo("Parameter runKey is not allowed for EtaAggregationJob");
        for (UUID id : List.of(future, offHour, notAnInstant, force, runKey)) {
            assertThat(request(id).get("status")).isEqualTo("REJECTED");
        }
    }

    @Test
    void aValidEtaRequestRunsTheJobAsMakeJobRunWould() {
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS).minusSeconds(3600);
        listRoute(route);
        Instant scheduled = hour.minusSeconds(3 * 3600);
        facts.arrival(scheduled.atZone(CHICAGO).toLocalDate(), scheduled, 60)
                .stop("S1")
                .insert();

        UUID id = jobRequest("EtaAggregationJob", "{\"hour\": \"" + hour + "\", \"force\": \"true\"}");
        Map<String, Object> request = awaitRequest(id);

        assertThat(request.get("status")).isEqualTo("DONE");
        assertThat((String) request.get("message")).startsWith("COMPLETED: Aggregated");
        assertThat(etaRows(route)).hasSize(1);
        assertThat(etaRows(route).getFirst().get("stop_id")).isEqualTo("S1");
    }

    @Test
    void aValidOtpRequestTakesAListWithPlusAsJobRunWritesIt() {
        LocalDate day = today().minusDays(1);
        otpArrivals(facts, day, 0, 0);
        otpArrivals(facts, day.minusDays(1), 0);

        UUID id = jobRequest("OtpScorecardJob", "{\"serviceDates\": \"" + day.minusDays(1) + "+" + day + "\"}");
        Map<String, Object> request = awaitRequest(id);

        assertThat(request.get("status")).isEqualTo("DONE");
        assertThat((String) request.get("message")).startsWith("COMPLETED: Scored 2 service dates");
        assertThat(otpRows(route).keySet()).containsExactly(day, day.minusDays(1));
    }

    @Test
    void theAnalyticsJobsAreInTheCatalogAndDeployed() {
        assertThat(launcher.job(PtiJob.ETA_AGGREGATION)).isPresent();
        assertThat(launcher.job(PtiJob.OTP_SCORECARD)).isPresent();
        assertThat(PtiJob.byName("EtaAggregationJob")).contains(PtiJob.ETA_AGGREGATION);
        assertThat(PtiJob.byName("OtpScorecardJob")).contains(PtiJob.OTP_SCORECARD);
        assertThat(PtiJob.ETA_AGGREGATION.identity().parameter()).isEqualTo("runKey");
        assertThat(PtiJob.OTP_SCORECARD.extraParameters()).containsExactly("serviceDates");
    }
}
