package dev.pti.etl.gtfs;

import dev.pti.common.error.FatalException;
import dev.pti.etl.raw.RawZone;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Step {@code fetch} (DOC-21 §3.1): copy the feed, decide from its hash whether there is anything to do, keep the zip
 * in the raw zone, extract it safely, check its structure and register the STAGED version. Exit codes {@code NOOP},
 * {@code REACTIVATE} and {@code REJECTED} steer the job flow.
 */
public class FetchFeedTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(FetchFeedTasklet.class);

    public static final String SOURCE_URI = "sourceUri";
    public static final String ALLOW_REACTIVATE = "allowReactivate";
    public static final String NOOP = "NOOP";
    public static final String REACTIVATE = "REACTIVATE";
    public static final String REJECTED = "REJECTED";
    /** Job context flag read by {@code activate}: the feed is a RETIRED version brought back. */
    public static final String REACTIVATING = "pti.gtfs.reactivate";

    /** Defaults for a missing {@code sourceUri}: {@code pti.gtfs.static.source}, then the bootstrap location. */
    public record Defaults(String source, String bootstrapLocation, String expectedSha256, int maxSamples) {}

    private final FeedSource source;
    private final RawZone raw;
    private final FeedWorkspace workspace;
    private final FeedExtractor extractor;
    private final FeedVersions versions;
    private final JobRepository jobRepository;
    private final Defaults defaults;

    public FetchFeedTasklet(
            FeedSource source,
            RawZone raw,
            FeedWorkspace workspace,
            FeedExtractor extractor,
            FeedVersions versions,
            JobRepository jobRepository,
            Defaults defaults) {
        this.source = source;
        this.raw = raw;
        this.workspace = workspace;
        this.extractor = extractor;
        this.versions = versions;
        this.jobRepository = jobRepository;
        this.defaults = defaults;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobExecution job = contribution.getStepExecution().getJobExecution();
        long instance = job.getJobInstance().getInstanceId();
        String sourceUri = sourceUri(job);
        URI uri;
        try {
            uri = source.check(sourceUri);
        } catch (IllegalArgumentException e) {
            throw new FatalException(e.getMessage(), e);
        }

        Path zip = workspace.zip(instance);
        String hash = source.copy(uri, zip);
        if (!defaults.expectedSha256().isEmpty() && !defaults.expectedSha256().equalsIgnoreCase(hash)) {
            throw new FatalException("Feed " + sourceUri + " has hash " + hash + ", expected "
                    + defaults.expectedSha256() + " (pti.gtfs.static.expected-sha256)");
        }
        boolean allowReactivate = Boolean.parseBoolean(job.getJobParameters().getString(ALLOW_REACTIVATE));
        Optional<FeedVersions.Existing> existing = versions.findByHash(hash);
        if (existing.isPresent()) {
            FeedVersions.Existing version = existing.get();
            switch (version.status()) {
                case "RETIRED" -> {
                    if (allowReactivate) {
                        log.info("Reactivating feed {}", hash);
                        ExecutionContext context = job.getExecutionContext();
                        context.putLong(FeedContext.FEED_VERSION_ID, version.id());
                        context.putString(FeedContext.FEED_HASH, hash);
                        context.putString(REACTIVATING, "true");
                        return exit(contribution, REACTIVATE, "Reactivating feed version " + version.id());
                    }
                    return noop(contribution, hash, version);
                }
                case "STAGED" -> {
                    Long loader = version.jobExecutionId();
                    JobExecution loading = loader == null ? null : jobRepository.getJobExecution(loader);
                    if (loading != null && loading.isRunning() && loader != job.getId()) {
                        throw new FatalException(
                                "Feed " + hash + " is being loaded by execution " + loader + " right now");
                    }
                    log.info("Removing orphan STAGED version {} of feed {}", version.id(), hash);
                    versions.purgeAll(version.id());
                }
                default -> {
                    return noop(contribution, hash, version);
                }
            }
        }

        Optional<String> sourceKey = source.rawKeyOf(uri);
        String key = sourceKey.orElse(raw.key(FeedSource.RAW_PREFIX + hash + ".zip"));
        if (sourceKey.isEmpty() && !raw.exists(key)) {
            raw.put(key, zip);
        }
        String rawObjectKey = raw.bucket() + "/" + key;

        FeedStructure.Inspection inspection;
        try {
            FeedExtractor.Extracted extracted =
                    FeedWorkspaceListener.extract(extractor, zip, workspace.files(instance));
            inspection = FeedStructure.inspect(workspace.files(instance), extracted, defaults.maxSamples());
        } catch (FeedRejectedException e) {
            GtfsIssues issues = new GtfsIssues(defaults.maxSamples());
            issues.add(e.check(), FeedStructure.fileSample("feed.zip", e.getMessage()));
            inspection = new FeedStructure.Inspection(null, Map.of(), issues);
        }
        String extraColumns = FeedReport.extraColumnsJson(inspection.extraColumns());
        java.time.ZoneId found = inspection.zone();
        String zone = found == null ? "" : found.getId();

        if (inspection.issues().hasErrors()) {
            String report = FeedReport.build(inspection.issues(), Map.of(), extraColumns, Map.of());
            OptionalLong id = versions.insert(hash, sourceUri, rawObjectKey, zone, "REJECTED", report, job.getId());
            log.error("Feed {} rejected by fetch: {}", hash, inspection.issues().checks());
            return exit(contribution, id.isPresent() ? REJECTED : NOOP, "Feed " + hash + " rejected");
        }
        OptionalLong id = versions.insert(hash, sourceUri, rawObjectKey, zone, "STAGED", null, job.getId());
        if (id.isEmpty()) {
            log.info("Feed {} was registered by another execution meanwhile", hash);
            return exit(contribution, NOOP, "Feed " + hash + " already loaded");
        }
        ExecutionContext context = job.getExecutionContext();
        context.putLong(FeedContext.FEED_VERSION_ID, id.getAsLong());
        context.putString(FeedContext.FEED_HASH, hash);
        context.putString(FeedContext.AGENCY_ZONE, zone);
        context.putString(FeedContext.FETCH_ISSUES, inspection.issues().toJson());
        context.putString(FeedContext.EXTRA_COLUMNS, extraColumns);
        log.info("Feed {} from {} staged as version {}", hash, sourceUri, id.getAsLong());
        contribution.setExitStatus(
                ExitStatus.COMPLETED.addExitDescription("Staged feed " + hash + " as version " + id.getAsLong()));
        return RepeatStatus.FINISHED;
    }

    private String sourceUri(JobExecution job) {
        String given = job.getJobParameters().getString(SOURCE_URI);
        if (given != null && !given.isBlank()) {
            return given;
        }
        if (!defaults.source().isEmpty()) {
            return defaults.source();
        }
        if (!defaults.bootstrapLocation().isEmpty()) {
            return defaults.bootstrapLocation();
        }
        throw new FatalException(
                "No sourceUri: set the parameter, pti.gtfs.static.source or pti.gtfs.bootstrap-location");
    }

    private static RepeatStatus noop(StepContribution contribution, String hash, FeedVersions.Existing version) {
        log.info("Feed {} already loaded as {}", hash, version.status());
        return exit(contribution, NOOP, "Feed " + hash + " already loaded as " + version.status());
    }

    private static RepeatStatus exit(StepContribution contribution, String code, String description) {
        contribution.setExitStatus(new ExitStatus(code, description));
        return RepeatStatus.FINISHED;
    }
}
