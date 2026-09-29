package dev.pti.etl.config;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.BatchSchedules;
import dev.pti.etl.batch.BatchSteps;
import dev.pti.etl.batch.MessageProcessorRouter;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.raw.RawZone;
import dev.pti.etl.replay.DeadLetterReader;
import dev.pti.etl.replay.DeadLetterReplays;
import dev.pti.etl.replay.DlqReplayWriter;
import dev.pti.etl.replay.ListObjectsTasklet;
import dev.pti.etl.replay.RawZoneReader;
import dev.pti.etl.replay.ReplayChunkWriter;
import dev.pti.etl.replay.ReplayRequestListener;
import dev.pti.etl.replay.ReplayRequestPoller;
import dev.pti.etl.replay.ReplayRequests;
import dev.pti.etl.write.FactChunkWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code DlqReplayJob}, {@code RawZoneReplayJob} and the poller of {@code ops.replay_request} (DOC-22). */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
public class ReplayJobsConfiguration {

    @Bean
    ReplayRequests replayRequests(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new ReplayRequests(jdbc, tx);
    }

    @Bean
    DeadLetterReplays deadLetterReplays(JdbcTemplate jdbc) {
        return new DeadLetterReplays(jdbc);
    }

    @Bean
    ReplayRequestListener replayRequestListener(ReplayRequests requests, JdbcTemplate jdbc, MeterRegistry meters) {
        return new ReplayRequestListener(requests, jdbc, meters);
    }

    @Bean
    ReplayRequestPoller replayRequestPoller(
            ReplayRequests requests,
            PtiJobLauncher launcher,
            BatchSchedules schedules,
            ThreadPoolTaskExecutor batchTaskExecutor,
            ReplayProperties replay) {
        return new ReplayRequestPoller(
                requests,
                launcher,
                schedules,
                () -> {
                    ThreadPoolExecutor pool = batchTaskExecutor.getThreadPoolExecutor();
                    return pool.getActiveCount() < pool.getMaximumPoolSize()
                            || pool.getQueue().remainingCapacity() > 0;
                },
                replay.poller().maxClaims());
    }

    /** Step scoped: two replays of different sources may run at once, each with its own reader. */
    @Bean
    @StepScope
    RawZoneReader rawZoneReader(RawZone raw) {
        return new RawZoneReader(raw);
    }

    @Bean
    @StepScope
    DeadLetterReader deadLetterReader(JdbcTemplate jdbc) {
        return new DeadLetterReader(jdbc);
    }

    @Bean(name = "DlqReplayJob")
    Job dlqReplayJob(
            BatchSteps steps,
            DeadLetterReader reader,
            MessageProcessorRouter processor,
            FactChunkWriter writer,
            DeadLetterReplays deadLetters,
            BusinessClock clock,
            ReplayRequestListener listener) {
        return steps.job(PtiJob.DLQ_REPLAY)
                .listener(listener)
                .start(steps.chunk(
                        "replayRecord", 1, reader, processor, new DlqReplayWriter(writer, deadLetters, clock)))
                .build();
    }

    @Bean(name = "RawZoneReplayJob")
    Job rawZoneReplayJob(
            BatchSteps steps,
            RawZone raw,
            RawZoneReader reader,
            MessageProcessorRouter processor,
            FactChunkWriter writer,
            DeadLetterReplays deadLetters,
            BusinessClock clock,
            ReplayRequestListener listener,
            ReplayProperties replay) {
        return steps.job(PtiJob.RAW_ZONE_REPLAY)
                .listener(listener)
                .start(steps.tasklet("listObjects", new ListObjectsTasklet(raw, replay.maxObjects())))
                .next(steps.chunk(
                        "replayRecords",
                        replay.chunkSize(),
                        reader,
                        processor,
                        new ReplayChunkWriter(writer, deadLetters, clock)))
                .build();
    }
}
