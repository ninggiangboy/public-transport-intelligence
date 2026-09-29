package dev.pti.etl.dq;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.WarehouseSupport;
import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.gtfs.FeedBuilder;
import dev.pti.etl.gtfs.FeedVersions;
import dev.pti.etl.gtfs.FetchFeedTasklet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DOC-16 §8 case 29: each post-write rule counts one more violation when a fixture breaks it, and one fewer when the
 * fixture is gone; every run writes a result row. Counts are compared, since the database is shared with other tests.
 */
class DataQualityIT extends BatchContextSupport {

    private static final ZoneId AGENCY = ZoneId.of("America/Chicago");

    @Autowired
    NamedParameterJdbcTemplate named;

    @Autowired
    DqCheckResults results;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    BusinessClock clock;

    @Autowired
    FeedVersions versions;

    @Autowired
    PtiJobLauncher launcher;

    private DataQualityTasklet tasklet;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        tasklet = new DataQualityTasklet(
                named,
                results,
                transactionManager,
                clock,
                AGENCY,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                id -> true,
                () -> versions.activeId().orElse(-1L),
                new SimpleMeterRegistry());
        today = LocalDate.ofInstant(clock.instant(), AGENCY);
    }

    private long run(PostWriteRule rule) {
        long before = rows(rule);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> tasklet.run(rule));
        assertThat(rows(rule)).as("a result row per run").isEqualTo(before + 1);
        return jdbc.queryForObject("""
                SELECT violation_count FROM ops.dq_check_result WHERE rule_id = ? ORDER BY id DESC LIMIT 1
                """, Long.class, rule.id());
    }

    private long rows(PostWriteRule rule) {
        return jdbc.queryForObject("SELECT count(*) FROM ops.dq_check_result WHERE rule_id = ?", Long.class, rule.id());
    }

    /** Runs the rule, applies the fixture, runs it again; then removes the fixture and runs a third time. */
    private void assertCounts(PostWriteRule rule, Runnable add, Runnable remove) {
        long baseline = run(rule);
        add.run();
        try {
            assertThat(run(rule)).as(rule.id() + " with the fixture").isEqualTo(baseline + 1);
        } finally {
            remove.run();
        }
        assertThat(run(rule)).as(rule.id() + " without it").isEqualTo(baseline);
    }

    private void sale(UUID id, String type, UUID refundOf, Instant createdAt, UUID batchId, LocalDate saleDate) {
        jdbc.update(
                """
                INSERT INTO dw.fact_ticket_sales (sale_date, transaction_id, sale_point_id, ticket_type, txn_type,
                    amount, currency, refund_of, status, created_at, source_updated_at, source_lsn, event_timestamp,
                    payload_hash, batch_id)
                VALUES (?, ?, 'KIOSK-DQ', 'SINGLE', ?, 2.50, 'USD', ?, 'COMPLETED', ?, ?, 1, ?, ?, ?)
                """,
                saleDate,
                id,
                type,
                refundOf,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                WarehouseSupport.hash(id.toString()),
                batchId);
    }

    private void deleteSale(UUID id) {
        jdbc.update("DELETE FROM dw.fact_ticket_sales WHERE transaction_id = ?", id);
    }

    private UUID knownBatch() {
        UUID batch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ops.etl_stream_batch (batch_id, source, listener_id, consumer_group, instance_id, offsets,
                    status, write_mode, records_read, records_written, records_skipped, records_duplicate,
                    started_at, finished_at)
                VALUES (?, 'TICKETING_SALES', 'dq-test', 'g', 'i', '{}', 'COMPLETED', 'BATCH', 1, 1, 0, 0, now(), now())
                """, batch);
        return batch;
    }

    @Test
    void dq20FactRowsNeedAKnownBatch() {
        UUID id = UUID.randomUUID();
        assertCounts(
                PostWriteRule.DQ_20,
                () -> sale(id, "SALE", null, clock.instant(), UUID.randomUUID(), today),
                () -> deleteSale(id));
    }

    @Test
    void dq21RowsInADefaultPartitionAreReported() {
        UUID id = UUID.randomUUID();
        assertCounts(
                PostWriteRule.DQ_21,
                () -> sale(id, "SALE", null, clock.instant(), knownBatch(), LocalDate.parse("2099-06-15")),
                () -> deleteSale(id));
    }

    @Test
    void dq22AnOrphanRefundCountsOnlyAfterTheGracePeriod() {
        UUID fresh = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID batch = knownBatch();
        sale(fresh, "REFUND", UUID.randomUUID(), clock.instant().minusSeconds(180), batch, today);
        try {
            assertCounts(
                    PostWriteRule.DQ_22,
                    () -> sale(old, "REFUND", UUID.randomUUID(), clock.instant().minusSeconds(600), batch, today),
                    () -> deleteSale(old));
        } finally {
            deleteSale(fresh);
        }
    }

    private void tripUpdate(String trip, int sequence, boolean observed, Instant arrival, Instant event) {
        jdbc.update(
                """
                INSERT INTO dw.fact_trip_update (service_date, trip_id, stop_sequence, route_id, direction_id, stop_id,
                    vehicle_id, schedule_relationship, arrival_time, is_observed, event_timestamp, payload_hash,
                    batch_id)
                VALUES (?, ?, ?, '18', 0, '51405', 'V-DQ', 'SCHEDULED', ?, ?, ?, ?, ?)
                """,
                today,
                trip,
                sequence,
                Timestamp.from(arrival),
                observed,
                Timestamp.from(event),
                WarehouseSupport.hash(trip + sequence),
                knownBatch());
    }

    private void deleteTrip(String trip) {
        jdbc.update("DELETE FROM dw.fact_trip_update WHERE trip_id = ?", trip);
    }

    private void ensureActiveFeed() throws Exception {
        if (versions.activeId().isPresent()) {
            return;
        }
        JobExecution load = awaitEnd(launcher.start(
                PtiJob.GTFS_STATIC_LOAD,
                JobParams.identity(PtiJob.GTFS_STATIC_LOAD, "manual:" + UUID.randomUUID())
                        .addString(
                                FetchFeedTasklet.SOURCE_URI,
                                FeedBuilder.mini().write(FEEDS).toUri().toString(),
                                false)
                        .toJobParameters()));
        assertThat(load.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void dq23TripUpdatesMustMatchTheActiveSchedule() throws Exception {
        ensureActiveFeed();
        String trip = "NOT-IN-FEED-" + UUID.randomUUID();
        Instant now = clock.instant();
        assertCounts(PostWriteRule.DQ_23, () -> tripUpdate(trip, 1, false, now, now), () -> deleteTrip(trip));
    }

    @Test
    void dq24AnObservedStopCannotBeInTheFuture() {
        String trip = "DQ24-" + UUID.randomUUID();
        Instant now = clock.instant();
        assertCounts(
                PostWriteRule.DQ_24,
                () -> tripUpdate(trip, 1, true, now.plusSeconds(600), now),
                () -> deleteTrip(trip));
    }

    @Test
    void dq25TheLatestPositionMustExistAsAFact() {
        String vehicle = "DQ25-" + UUID.randomUUID().toString().substring(0, 8);
        assertCounts(
                PostWriteRule.DQ_25,
                () -> jdbc.update("""
                        INSERT INTO dw.vehicle_position_latest (vehicle_id, service_date, route_id, trip_id,
                            direction_id, lat, lon, current_stop_sequence, stop_id, current_status, event_timestamp,
                            batch_id)
                        VALUES (?, ?, '18', 'T', 0, 44.9, -93.2, 1, 'S', 'IN_TRANSIT_TO', now(), ?)
                        """, vehicle, today, UUID.randomUUID()),
                () -> jdbc.update("DELETE FROM dw.vehicle_position_latest WHERE vehicle_id = ?", vehicle));
    }

    @Test
    void dq26RealtimeOnlyVehiclesAreRecordedButNeverBreach() {
        String vehicle = "DQ26-" + UUID.randomUUID().toString().substring(0, 8);
        assertCounts(
                PostWriteRule.DQ_26,
                () -> jdbc.update("INSERT INTO dw.dim_vehicle (vehicle_id, source) VALUES (?, 'REALTIME')", vehicle),
                () -> jdbc.update("DELETE FROM dw.dim_vehicle WHERE vehicle_id = ?", vehicle));
        assertThat(PostWriteRule.DQ_26.breached(1000, 1000L)).isFalse();
    }

    @Test
    void theScheduledJobRunsEveryDueRuleOnceAndMetricsReadTheResults() {
        JobExecution first = awaitEnd(launcher.launchIfNew(
                        PtiJob.DATA_QUALITY,
                        JobParams.slot(PtiJob.DATA_QUALITY, Instant.now().plusSeconds(86_400 * 3650L), 5))
                .orElseThrow());
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Map<String, DqCheckResults.Latest> latest = results.latest();
        assertThat(latest).containsKeys("DQ-20", "DQ-21", "DQ-22", "DQ-23", "DQ-24", "DQ-25", "DQ-26");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DqMetrics metrics = new DqMetrics(results, meters, id -> !id.equals("DQ-26"));
        metrics.refresh();
        assertThat(meters.get("pti.dq.check.last.run.timestamp.seconds")
                        .tag("rule", "DQ-20")
                        .gauge()
                        .value())
                .isGreaterThan(0);
        assertThat(meters.get("pti.dq.check.interval.seconds")
                        .tag("rule", "DQ-22")
                        .gauge()
                        .value())
                .isEqualTo(300.0);
        assertThat(meters.find("pti.dq.check.violations").tag("rule", "DQ-26").gauge())
                .as("a rule turned off has no gauge")
                .isNull();
    }
}
