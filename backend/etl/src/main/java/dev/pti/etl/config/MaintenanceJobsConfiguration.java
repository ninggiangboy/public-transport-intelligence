package dev.pti.etl.config;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.adapter.in.batch.BatchSteps;
import dev.pti.etl.batch.domain.PtiJob;
import dev.pti.etl.maintenance.adapter.in.batch.BatchMetadataCleanupTasklet;
import dev.pti.etl.maintenance.adapter.in.batch.BatchedPurgeTasklet;
import dev.pti.etl.maintenance.adapter.in.batch.PartitionMaintenanceTasklet;
import dev.pti.etl.maintenance.adapter.out.jdbc.JdbcBatchMetadataStore;
import dev.pti.etl.maintenance.adapter.out.jdbc.JdbcExpiredRows;
import dev.pti.etl.maintenance.adapter.out.jdbc.JdbcPartitions;
import dev.pti.etl.maintenance.adapter.out.metrics.MicrometerRetentionMetrics;
import dev.pti.etl.maintenance.application.CleanupBatchMetadata;
import dev.pti.etl.maintenance.application.MaintainPartitions;
import dev.pti.etl.maintenance.application.ManagedTable;
import dev.pti.etl.maintenance.application.PurgeExpiredRows;
import dev.pti.etl.maintenance.application.PurgeTarget;
import dev.pti.etl.maintenance.application.port.BatchMetadataStore;
import dev.pti.etl.maintenance.application.port.ExpiredRows;
import dev.pti.etl.maintenance.application.port.Partitions;
import dev.pti.etl.maintenance.application.port.RetentionMetrics;
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

    @Bean
    RetentionMetrics maintenanceRetentionMetrics(MeterRegistry meters) {
        return new MicrometerRetentionMetrics(meters);
    }

    @Bean
    ExpiredRows maintenanceExpiredRows(JdbcTemplate jdbc) {
        return new JdbcExpiredRows(jdbc);
    }

    @Bean
    BatchMetadataStore maintenanceBatchMetadataStore(JdbcTemplate jdbc) {
        return new JdbcBatchMetadataStore(jdbc);
    }

    @Bean
    Partitions maintenancePartitions(JdbcTemplate jdbc) {
        return new JdbcPartitions(jdbc);
    }

    @Bean
    PurgeExpiredRows purgeExpiredRows(
            ExpiredRows maintenanceExpiredRows, RetentionMetrics maintenanceRetentionMetrics) {
        return new PurgeExpiredRows(maintenanceExpiredRows, maintenanceRetentionMetrics);
    }

    @Bean
    CleanupBatchMetadata cleanupBatchMetadata(
            BatchMetadataStore maintenanceBatchMetadataStore, RetentionMetrics maintenanceRetentionMetrics) {
        return new CleanupBatchMetadata(maintenanceBatchMetadataStore, maintenanceRetentionMetrics);
    }

    @Bean
    MaintainPartitions maintainPartitions(
            Partitions maintenancePartitions, RetentionMetrics maintenanceRetentionMetrics) {
        return new MaintainPartitions(maintenancePartitions, maintenanceRetentionMetrics);
    }

    @Bean(name = "PartitionMaintenanceJob")
    Job partitionMaintenanceJob(
            BatchSteps steps,
            MaintainPartitions maintenance,
            BusinessClock clock,
            GtfsProperties gtfs,
            RetentionProperties retention,
            PlatformProperties platform) {
        int ahead = platform.partition().precreateDays();
        List<ManagedTable> tables = List.of(
                new ManagedTable("fact_vehicle_position", ahead, retention.vehiclePosition()),
                new ManagedTable("fact_trip_update", ahead, retention.tripUpdate()),
                new ManagedTable("fact_ticket_sales", TICKET_DAYS_AHEAD, retention.ticketSales()));
        return steps.job(PtiJob.PARTITION_MAINTENANCE)
                .start(steps.tasklet(
                        "maintainPartitions",
                        new PartitionMaintenanceTasklet(
                                maintenance, clock, gtfs.staticFeed().zone(), tables)))
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
            PurgeExpiredRows purge,
            RetentionProperties retention,
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
                .start(steps.tasklet("purgeOps", new BatchedPurgeTasklet(purge, realClock, targets, PURGE_BATCH)))
                .next(purgeInsight)
                .build();
    }

    @Bean(name = "BatchMetadataCleanupJob")
    Job batchMetadataCleanupJob(BatchSteps steps, CleanupBatchMetadata cleanup, RetentionProperties retention) {
        return steps.job(PtiJob.BATCH_METADATA_CLEANUP)
                .start(steps.tasklet(
                        "purgeBatchMetadata",
                        new BatchMetadataCleanupTasklet(
                                cleanup, Clock.systemDefaultZone(), retention.batchMetadata(), METADATA_BATCH)))
                .build();
    }

    @Bean(name = "DedupRegistryCleanupJob")
    Job dedupRegistryCleanupJob(
            BatchSteps steps, PurgeExpiredRows purge, EtlProperties etl, BusinessClock businessClock) {
        Clock realClock = Clock.offset(businessClock, businessClock.offset().negated());
        List<PurgeTarget> targets = List.of(new PurgeTarget(
                "ops.dedup_registry", "first_seen_at < ?", etl.dedup().ttl()));
        return steps.job(PtiJob.DEDUP_REGISTRY_CLEANUP)
                .start(steps.tasklet("purgeDedup", new BatchedPurgeTasklet(purge, realClock, targets, PURGE_BATCH)))
                .build();
    }
}
