package dev.pti.etl.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

/** G-03 (DOC-21 §10): the real Minneapolis feed, nightly only. */
@Tag("slow")
class GtfsRealFeedIT extends BatchContextSupport {

    @Autowired
    PtiJobLauncher launcher;

    @Test
    void g03TheRealFeedLoadsWithinThreeMinutes() throws Exception {
        Path source =
                Path.of(System.getProperty("pti.repo-root", "."), "sample-data/gtfs/metrotransit-mn-20260926.zip");
        assumeTrue(Files.exists(source), "sample-data/gtfs is not present");
        Path zip = Files.copy(
                source, FEEDS.resolve("real-" + UUID.randomUUID() + ".zip"), StandardCopyOption.REPLACE_EXISTING);

        long start = System.nanoTime();
        JobExecution execution = awaitEnd(
                launcher.start(
                        PtiJob.GTFS_STATIC_LOAD,
                        JobParams.identity(PtiJob.GTFS_STATIC_LOAD, "manual:" + UUID.randomUUID())
                                .addString(
                                        FetchFeedTasklet.SOURCE_URI, zip.toUri().toString(), false)
                                .toJobParameters()),
                Duration.ofMinutes(5));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(execution.getExitStatus().getExitCode()).isIn("COMPLETED", "NOOP");
        long version = jdbc.queryForObject(
                "SELECT feed_version_id FROM dw.gtfs_feed_version WHERE feed_hash = ?",
                Long.class,
                FeedSource.sha256(zip));
        assertThat(count("dw.dim_route", version, "")).isEqualTo(127);
        assertThat(count("dw.dim_stop", version, "")).isEqualTo(8183);
        assertThat(count("dw.dim_stop", version, "AND location_type = 0")).isEqualTo(8155);
        assertThat(count("dw.gtfs_stop_time", version, "")).isEqualTo(872_717);
        assertThat(jdbc.queryForObject(
                        "SELECT typical_headway_seconds FROM dw.dim_route WHERE feed_version_id = ? AND route_id = '18'",
                        Integer.class,
                        version))
                .isEqualTo(600);
        if (execution.getExitStatus().getExitCode().equals("COMPLETED")) {
            assertThat(took).isLessThanOrEqualTo(Duration.ofMinutes(3));
        }
    }

    private long count(String table, long version, String filter) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE feed_version_id = ? " + filter, Long.class, version);
    }
}
