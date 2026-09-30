package dev.pti.etl.batch;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The job catalog of DOC-19 §2: the bean name, what identifies an instance, which extra parameters a
 * {@code job_request} may pass, and how {@link StaleExecutionRecoverer} treats a stale execution.
 */
public enum PtiJob {
    GTFS_STATIC_LOAD(
            "GtfsStaticLoadJob", Identity.RUN_KEY, Set.of("sourceUri", "allowReactivate"), Stale.RESTART, true),
    DLQ_REPLAY("DlqReplayJob", Identity.REPLAY_REQUEST, Set.of(), Stale.FAIL_REPLAY_REQUEST, false),
    RAW_ZONE_REPLAY("RawZoneReplayJob", Identity.REPLAY_REQUEST, Set.of(), Stale.FAIL_REPLAY_REQUEST, false),
    PARTITION_MAINTENANCE("PartitionMaintenanceJob", Identity.RUN_DATE, Set.of(), Stale.RESTART, true),
    OPS_RETENTION("OpsRetentionJob", Identity.RUN_DATE, Set.of(), Stale.RESTART, true),
    BATCH_METADATA_CLEANUP("BatchMetadataCleanupJob", Identity.RUN_DATE, Set.of(), Stale.RESTART, true),
    DEDUP_REGISTRY_CLEANUP("DedupRegistryCleanupJob", Identity.SLOT, Set.of(), Stale.FAIL, true),
    DATA_QUALITY("DataQualityJob", Identity.SLOT, Set.of(), Stale.FAIL, true),
    ETA_AGGREGATION("EtaAggregationJob", Identity.RUN_KEY, Set.of("hour", "force"), Stale.RESTART, true),
    OTP_SCORECARD("OtpScorecardJob", Identity.RUN_KEY, Set.of("serviceDates"), Stale.RESTART, true);

    /** The identifying job parameter (DOC-19 §2, "Tham số định danh"). */
    public enum Identity {
        /** {@code runKey}: {@code startup:<date>}, {@code scheduled:<date>} or {@code manual:<requestId>}. */
        RUN_KEY("runKey"),
        /** {@code runDate}: an ISO date. */
        RUN_DATE("runDate"),
        /** {@code slot}: the start of a schedule slot, an ISO instant. */
        SLOT("slot"),
        /** {@code replayRequestId}: one instance per {@code ops.replay_request}. */
        REPLAY_REQUEST("replayRequestId");

        private final String parameter;

        Identity(String parameter) {
            this.parameter = parameter;
        }

        public String parameter() {
            return parameter;
        }
    }

    /** What happens to an execution that stopped updating (DOC-19 §7.2). */
    public enum Stale {
        /** Mark it FAILED and restart it from the last committed chunk. */
        RESTART,
        /** Mark it FAILED only: a short tasklet that simply runs again in its next slot. */
        FAIL,
        /** Mark it FAILED and fail its {@code replay_request}; DOC-22 decides whether to retry. */
        FAIL_REPLAY_REQUEST
    }

    private final String jobName;
    private final Identity identity;
    private final Set<String> extraParameters;
    private final Stale stale;
    private final boolean manualRun;

    PtiJob(String jobName, Identity identity, Set<String> extraParameters, Stale stale, boolean manualRun) {
        this.jobName = jobName;
        this.identity = identity;
        this.extraParameters = extraParameters;
        this.stale = stale;
        this.manualRun = manualRun;
    }

    public String jobName() {
        return jobName;
    }

    public Identity identity() {
        return identity;
    }

    /** Parameters a {@code RUN} request may pass besides the identifying one. */
    public Set<String> extraParameters() {
        return extraParameters;
    }

    public Stale stale() {
        return stale;
    }

    /** False for the replay jobs: they start from {@code ops.replay_request} only (DOC-22). */
    public boolean manualRun() {
        return manualRun;
    }

    public static Optional<PtiJob> byName(String jobName) {
        return Arrays.stream(values()).filter(j -> j.jobName.equals(jobName)).findFirst();
    }
}
