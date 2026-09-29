package dev.pti.etl.batch;

import static org.awaitility.Awaitility.await;

import dev.pti.db.MigratedDatabases;
import dev.pti.etl.EtlApplication;
import dev.pti.etl.gtfs.LocalRawZone;
import dev.pti.etl.raw.RawZone;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The {@code etl-batch} application against the migrated warehouse. Schedules are off and retention is long, so that
 * the jobs only run when a test starts them and never drop the fixture partitions other tests write to.
 */
@SpringBootTest(
        classes = EtlApplication.class,
        properties = {
            "pti.batch.schedule.partition-maintenance=-",
            "pti.batch.schedule.ops-retention=-",
            "pti.batch.schedule.batch-metadata-cleanup=-",
            "pti.batch.schedule.dedup-registry-cleanup=-",
            "pti.batch.schedule.data-quality=-",
            "pti.batch.poller.interval=1s",
            "pti.retention.vehicle-position=3650d",
            "pti.retention.trip-update=3650d",
            "pti.retention.ticket-sales=3650d",
            "pti.etl.retry.initial-interval=10ms",
            "pti.etl.retry.max-interval=40ms",
            "pti.etl.batch.skip-min-sample=100",
        })
@ActiveProfiles({"batch", "test"})
@Import(BatchContextSupport.TestRawZone.class)
public abstract class BatchContextSupport {

    /** Feeds a test may load ({@code pti.gtfs.static.allowed-dirs}), the work dir and the local raw zone. */
    public static final Path FEEDS = temp("pti-feeds");

    public static final Path WORK = temp("pti-gtfs-work");

    public static final Path RAW = temp("pti-raw");

    private static Path temp(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRawZone {
        @Bean
        @Primary
        RawZone localRawZone() {
            return new LocalRawZone(RAW);
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JobRepository jobRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MigratedDatabases.jdbcUrl("pti_warehouse"));
        registry.add("spring.datasource.password", () -> MigratedDatabases.password("etl_writer"));
        registry.add("server.port", () -> "0");
        registry.add("management.server.port", () -> "0");
        registry.add("pti.gtfs.static.allowed-dirs", FEEDS::toString);
        registry.add("pti.gtfs.static.work-dir", WORK::toString);
    }

    /** Jobs run on the asynchronous executor: wait until the execution has ended. */
    public JobExecution awaitEnd(JobExecution execution) {
        return awaitEnd(execution, Duration.ofSeconds(60));
    }

    public JobExecution awaitEnd(JobExecution execution, Duration timeout) {
        long id = execution.getId();
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !jobRepository.getJobExecution(id).isRunning());
        return jobRepository.getJobExecution(id);
    }
}
