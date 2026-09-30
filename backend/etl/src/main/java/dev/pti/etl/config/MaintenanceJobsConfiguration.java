package dev.pti.etl.config;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.BatchSteps;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.maintenance.BatchMetadataCleanupTasklet;
import dev.pti.etl.batch.maintenance.BatchedPurgeTasklet;
import dev.pti.etl.batch.maintenance.BatchedPurgeTasklet.PurgeTarget;
import dev.pti.etl.batch.maintenance.PartitionMaintenanceTasklet;
import dev.pti.etl.batch.maintenance.PartitionMaintenanceTasklet.ManagedTable;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** The tasklet jobs of DOC-18 §5: partitions, retention, Spring Batch metadata and the dedup registry. */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class MaintenanceJobsConfiguration {

    /** DOC-18 §5: no delete touches more rows than this in one transaction. */
    static final int PURGE_BATCH = 5000;

    /** Job instances per transaction; each takes six deletes. */
    static final int METADATA_BATCH = 500;

    /** Monthly ticket partitions are created further ahead (DOC-14 §7.4). */
    static final int TICKET_DAYS_AHEAD = 40;

    @Bean(name = "PartitionMaintenanceJob")
    Job partitionMaintenanceJob(
            BatchSteps steps,
            JdbcTemplate jdbc,
            BusinessClock clock,
            GtfsProperties gtfs,
            RetentionProperties retention,
            PlatformProperties platform,
            MeterRegistry meters) {
        int ahead = platform.partition().precreateDays();
        List<ManagedTable> tables = List.of(
                new ManagedTable("fact_vehicle_position", ahead, retention.vehiclePosition()),
                new ManagedTable("fact_trip_update", ahead, retention.tripUpdate()),
                new ManagedTable("fact_ticket_sales", TICKET_DAYS_AHEAD, retention.ticketSales()));
        return steps.job(PtiJob.PARTITION_MAINTENANCE)
                .start(steps.tasklet(
                        "maintainPartitions",
                        new PartitionMaintenanceTasklet(
                                jdbc, clock, gtfs.staticFeed().zone(), tables, meters)))
                .build();
    }

    /**
     * DOC-18 §1.1. {@code purgeOps} deletes the {@code ops} rows, then {@code purgeInsight} (DOC-23 §12.3) the expired
     * rows of {@code insight}. Unfinished rows never match their condition.
     *
     * @param realClock the audit columns of {@code ops} expire against real time: the business clock minus its offset
     */
    @Bean(name = "OpsRetentionJob")
    Job opsRetentionJob(
            BatchSteps steps,
            JdbcTemplate jdbc,
            RetentionProperties retention,
            MeterRegistry meters,
            BusinessClock businessClock,
            @Qualifier("purgeInsightStep") Step purgeInsight) {
        Clock realClock = Clock.offset(businessClock, businessClock.offset().negated());
        List<PurgeTarget> targets = List.of(
                new PurgeTarget("ops.etl_stream_batch", "started_at < ?", retention.etlStreamBatch()),
                new PurgeTarget(
                        "ops.dead_letter",
                        "status IN ('REPLAYED', 'DISCARDED', 'RESOLVED') AND coalesce(resolved_at, updated_at) < ?",
                        retention.deadLetterResolved()),
                new PurgeTarget("ops.replay_request", "finished_at < ?", retention.replayRequest()),
                new PurgeTarget("ops.job_request", "finished_at < ?", retention.jobRequest()),
                new PurgeTarget("ops.dq_check_result", "checked_at < ?", retention.dqCheckResult()),
                new PurgeTarget("ops.alert_event", "created_at < ?", retention.alertEvent()));
        return steps.job(PtiJob.OPS_RETENTION)
                .start(steps.tasklet(
                        "purgeOps", new BatchedPurgeTasklet(jdbc, realClock, targets, PURGE_BATCH, meters)))
                .next(purgeInsight)
                .build();
    }

    @Bean(name = "BatchMetadataCleanupJob")
    Job batchMetadataCleanupJob(
            BatchSteps steps, JdbcTemplate jdbc, RetentionProperties retention, MeterRegistry meters) {
        return steps.job(PtiJob.BATCH_METADATA_CLEANUP)
                .start(steps.tasklet(
                        "purgeBatchMetadata",
                        new BatchMetadataCleanupTasklet(
                                jdbc, Clock.systemDefaultZone(), retention.batchMetadata(), METADATA_BATCH, meters)))
                .build();
    }

    @Bean(name = "DedupRegistryCleanupJob")
    Job dedupRegistryCleanupJob(BatchSteps steps, JdbcTemplate jdbc, EtlProperties etl, MeterRegistry meters) {
        List<PurgeTarget> targets = List.of(new PurgeTarget(
                "ops.dedup_registry", "first_seen_at < ?", etl.dedup().ttl()));
        return steps.job(PtiJob.DEDUP_REGISTRY_CLEANUP)
                .start(steps.tasklet(
                        "purgeDedup", new BatchedPurgeTasklet(jdbc, Clock.systemUTC(), targets, PURGE_BATCH, meters)))
                .build();
    }
}
