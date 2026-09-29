package dev.pti.etl.config;

import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.BatchSteps;
import dev.pti.etl.batch.JobParameterCheck;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.gtfs.FeedExtractor;
import dev.pti.etl.gtfs.FeedSource;
import dev.pti.etl.gtfs.FeedVersions;
import dev.pti.etl.gtfs.FeedWorkspace;
import dev.pti.etl.gtfs.FeedWorkspaceListener;
import dev.pti.etl.gtfs.FetchFeedTasklet;
import dev.pti.etl.gtfs.GtfsFileReader;
import dev.pti.etl.gtfs.GtfsInsert;
import dev.pti.etl.gtfs.GtfsJobListener;
import dev.pti.etl.gtfs.GtfsRow;
import dev.pti.etl.gtfs.GtfsRowProcessor;
import dev.pti.etl.gtfs.GtfsSkipCollector;
import dev.pti.etl.gtfs.GtfsSkipPolicy;
import dev.pti.etl.gtfs.GtfsTable;
import dev.pti.etl.gtfs.GtfsTriggers;
import dev.pti.etl.gtfs.RetireFeedsTasklet;
import dev.pti.etl.gtfs.SimpleFeedTasklets;
import dev.pti.etl.gtfs.ValidateFeedTasklet;
import dev.pti.etl.raw.RawZone;
import dev.pti.etl.raw.S3RawZone;
import dev.pti.etl.reference.ReferenceDataRefresher;
import dev.pti.etl.write.SqlResource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.FlowJobBuilder;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/** {@code GtfsStaticLoadJob} (DOC-21) and the raw zone it keeps feeds in. */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class GtfsJobConfiguration {

    @Bean
    RawZone rawZone(S3Client s3, PlatformProperties platform) {
        return new S3RawZone(s3, platform.s3().bucket(), platform.s3().rawPrefix());
    }

    @Bean
    FeedWorkspace feedWorkspace(GtfsProperties gtfs) {
        return new FeedWorkspace(gtfs.staticFeed().workDir());
    }

    @Bean
    FeedExtractor feedExtractor(GtfsProperties gtfs) {
        return new FeedExtractor(
                gtfs.staticFeed().maxUncompressedSize().toBytes(),
                gtfs.staticFeed().maxEntries());
    }

    @Bean
    FeedSource feedSource(GtfsProperties gtfs, RawZone raw) {
        return new FeedSource(gtfs.staticFeed().allowedDirs(), gtfs.staticFeed().allowHttp(), raw);
    }

    @Bean
    FeedVersions feedVersions(JdbcTemplate jdbc) {
        return new FeedVersions(jdbc);
    }

    /** G-13: a {@code job_request} with a source outside DOC-21 §1.1 is rejected before anything runs. */
    @Bean
    JobParameterCheck feedSourceCheck(FeedSource source) {
        return (job, name, value) -> {
            if (job == PtiJob.GTFS_STATIC_LOAD && name.equals(FetchFeedTasklet.SOURCE_URI)) {
                source.check(value);
            }
        };
    }

    @Bean
    GtfsTriggers gtfsTriggers(
            PtiJobLauncher launcher,
            FeedVersions versions,
            JobRepository jobRepository,
            FeedWorkspace workspace,
            BusinessClock clock,
            GtfsProperties gtfs) {
        return new GtfsTriggers(
                launcher,
                versions,
                jobRepository,
                workspace,
                clock,
                gtfs.staticFeed().zone(),
                gtfs.bootstrapLocation(),
                gtfs.staticFeed().source());
    }

    @Bean(name = "GtfsStaticLoadJob")
    Job gtfsStaticLoadJob(
            BatchSteps steps,
            JobRepository jobRepository,
            NamedParameterJdbcTemplate named,
            JdbcTemplate jdbc,
            FeedSource source,
            RawZone raw,
            FeedWorkspace workspace,
            FeedExtractor extractor,
            FeedVersions versions,
            ReferenceDataRefresher refresher,
            ErrorClassifier classifier,
            BusinessClock clock,
            GtfsProperties gtfs,
            MeterRegistry meters) {
        GtfsProperties.Static feed = gtfs.staticFeed();
        FeedWorkspaceListener workspaceListener = new FeedWorkspaceListener(workspace, extractor, raw);
        GtfsSkipPolicy skipPolicy = new GtfsSkipPolicy(classifier, feed.maxRowErrors());
        GtfsSkipCollector collector = new GtfsSkipCollector(feed.reportSamples());

        Step fetch = steps.tasklet(
                "fetch",
                new FetchFeedTasklet(
                        source,
                        raw,
                        workspace,
                        extractor,
                        versions,
                        jobRepository,
                        new FetchFeedTasklet.Defaults(
                                feed.source(), gtfs.bootstrapLocation(), feed.expectedSha256(), feed.reportSamples())));
        List<Step> loads = new ArrayList<>();
        for (GtfsTable table : GtfsTable.LOAD_ORDER) {
            loads.add(loadStep(
                    steps,
                    table,
                    insertWriter(named, table.insertSql()),
                    workspace,
                    workspaceListener,
                    skipPolicy,
                    collector));
        }
        loads.add(
                steps.tasklet("loadFeedInfo", SimpleFeedTasklets.loadFeedInfo(workspace, versions), workspaceListener));
        Step validate =
                steps.tasklet("validate", new ValidateFeedTasklet(named, versions, clock, feed.reportSamples()));
        Step reject = steps.tasklet("reject", SimpleFeedTasklets.reject(versions));
        Step finalizeStep = steps.tasklet("finalize", SimpleFeedTasklets.finalizeFeed(named));
        Step activate = steps.tasklet("activate", SimpleFeedTasklets.activate(named, versions, refresher));
        Step vehicles = loadStep(
                steps,
                GtfsTable.VEHICLES,
                insertWriter(named, SqlResource.load("upsert_dim_vehicle_feed")),
                workspace,
                workspaceListener,
                skipPolicy,
                collector);
        Step retire = steps.tasklet(
                "retire",
                new RetireFeedsTasklet(jdbc, versions, feed.keepVersions(), feed.rejectedRetention(), meters));

        FlowBuilder<FlowJobBuilder> flow = steps.job(PtiJob.GTFS_STATIC_LOAD)
                .listener(new GtfsJobListener(jobRepository, workspace, meters))
                .start(fetch)
                .on(FetchFeedTasklet.NOOP)
                .end(FetchFeedTasklet.NOOP)
                .from(fetch)
                .on(FetchFeedTasklet.REJECTED)
                .end(FetchFeedTasklet.REJECTED)
                .from(fetch)
                .on(FetchFeedTasklet.REACTIVATE)
                .to(activate)
                .from(fetch)
                .on("COMPLETED")
                .to(loads.getFirst());
        for (int i = 1; i < loads.size(); i++) {
            flow = flow.next(loads.get(i));
        }
        return flow.next(validate)
                .from(validate)
                .on(FetchFeedTasklet.REJECTED)
                .to(reject)
                .from(reject)
                .on("COMPLETED")
                .end(FetchFeedTasklet.REJECTED)
                .from(validate)
                .on("COMPLETED")
                .to(finalizeStep)
                .next(activate)
                .from(activate)
                .on("COMPLETED")
                .to(vehicles)
                .next(retire)
                .from(fetch)
                .on("*")
                .fail()
                .from(validate)
                .on("*")
                .fail()
                .from(reject)
                .on("*")
                .fail()
                .from(activate)
                .on("*")
                .fail()
                .end()
                .build();
    }

    private static JdbcBatchItemWriter<GtfsInsert> insertWriter(NamedParameterJdbcTemplate named, String sql) {
        JdbcBatchItemWriter<GtfsInsert> writer = new JdbcBatchItemWriter<>();
        writer.setJdbcTemplate(named);
        writer.setSql(sql);
        writer.setItemSqlParameterSourceProvider(GtfsInsert::params);
        writer.setAssertUpdates(true);
        writer.afterPropertiesSet();
        return writer;
    }

    /**
     * A GTFS load step (DOC-21 §3.2): row errors are skipped into the report, transient errors retried. The fault
     * points are wired to {@code loadStopTimes} only, the step tests B-07 and G-06 interrupt.
     */
    @SuppressWarnings("removal")
    private static Step loadStep(
            BatchSteps steps,
            GtfsTable table,
            ItemWriter<GtfsInsert> writer,
            FeedWorkspace workspace,
            FeedWorkspaceListener workspaceListener,
            GtfsSkipPolicy skipPolicy,
            GtfsSkipCollector collector) {
        SimpleStepBuilder<GtfsRow, GtfsInsert> builder = new StepBuilder(table.stepName(), steps.jobRepository())
                .<GtfsRow, GtfsInsert>chunk(table.chunkSize(), steps.transactionManager())
                .reader(new GtfsFileReader(table, workspace))
                .processor(new GtfsRowProcessor(table))
                .writer(writer);
        builder.listener((StepExecutionListener) steps.batchIdListener());
        builder.listener((StepExecutionListener) workspaceListener);
        builder.listener((StepExecutionListener) collector);
        if (table == GtfsTable.STOP_TIMES) {
            builder.listener((ItemWriteListener<Object>) steps.faultListener());
        }
        return builder.faultTolerant()
                .processorNonTransactional()
                .skipPolicy(skipPolicy)
                .retryPolicy(steps.retryPolicy())
                .backOffPolicy(steps.backOffPolicy())
                .listener((SkipListener<GtfsRow, GtfsInsert>) collector)
                .build();
    }
}
