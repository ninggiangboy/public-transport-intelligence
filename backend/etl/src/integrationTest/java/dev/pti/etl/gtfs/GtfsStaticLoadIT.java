package dev.pti.etl.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.common.json.MessageJson;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.fault.ConfigurableFaultInjector;
import dev.pti.etl.fault.FaultAction;
import dev.pti.etl.fault.FaultPoint;
import dev.pti.etl.reference.ReferenceDataHolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;

/** {@code GtfsStaticLoadJob} on the mini feed (DOC-21 §10). */
class GtfsStaticLoadIT extends BatchContextSupport {

    @Autowired
    PtiJobLauncher launcher;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    ConfigurableFaultInjector faults;

    @Autowired
    ReferenceDataHolder reference;

    @AfterEach
    void disarm() {
        faults.disarmAll();
    }

    private static JobParameters parameters(Path zip, boolean allowReactivate) {
        return JobParams.identity(PtiJob.GTFS_STATIC_LOAD, "manual:" + UUID.randomUUID())
                .addString(FetchFeedTasklet.SOURCE_URI, zip.toUri().toString(), false)
                .addString(FetchFeedTasklet.ALLOW_REACTIVATE, String.valueOf(allowReactivate), false)
                .toJobParameters();
    }

    private JobExecution load(Path zip) throws Exception {
        return awaitEnd(launcher.start(PtiJob.GTFS_STATIC_LOAD, parameters(zip, false)));
    }

    private JobExecution loaded(FeedBuilder feed) throws Exception {
        JobExecution execution = load(feed.write(FEEDS));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as(() -> String.valueOf(report(versionOf(execution))))
                .isEqualTo("COMPLETED");
        return execution;
    }

    private static long versionOf(JobExecution execution) {
        return execution.getExecutionContext().getLong(FeedContext.FEED_VERSION_ID);
    }

    private String status(long version) {
        return jdbc.queryForObject(
                "SELECT status FROM dw.gtfs_feed_version WHERE feed_version_id = ?", String.class, version);
    }

    private JsonNode report(long version) {
        String json = jdbc.queryForObject(
                "SELECT validation_report::text FROM dw.gtfs_feed_version WHERE feed_version_id = ?",
                String.class,
                version);
        return json == null
                ? MessageJson.mapper().createObjectNode()
                : MessageJson.mapper().readTree(json);
    }

    private long count(String table, long version) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE feed_version_id = ?", Long.class, version);
    }

    private long versionByHash(Path zip) {
        return jdbc.queryForObject(
                "SELECT feed_version_id FROM dw.gtfs_feed_version WHERE feed_hash = ?",
                Long.class,
                FeedSource.sha256(zip));
    }

    private long activeVersion() {
        return jdbc.queryForObject(
                "SELECT feed_version_id FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'", Long.class);
    }

    private void ensureActive() throws Exception {
        Long active =
                jdbc.queryForObject("SELECT count(*) FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'", Long.class);
        if (active == 0) {
            loaded(FeedBuilder.mini());
        }
    }

    @Test
    void g01TheMiniFeedLoadsAndBecomesActive() throws Exception {
        FeedBuilder feed = FeedBuilder.mini();
        JobExecution execution = loaded(feed);
        long version = versionOf(execution);

        assertThat(status(version)).isEqualTo("ACTIVE");
        assertThat(count("dw.dim_agency", version)).isEqualTo(feed.rows("agency.txt"));
        assertThat(count("dw.dim_route", version)).isEqualTo(feed.rows("routes.txt"));
        assertThat(count("dw.dim_stop", version)).isEqualTo(feed.rows("stops.txt"));
        assertThat(count("dw.gtfs_trip", version)).isEqualTo(feed.rows("trips.txt"));
        assertThat(count("dw.gtfs_stop_time", version)).isEqualTo(feed.rows("stop_times.txt"));
        assertThat(count("dw.gtfs_shape", version)).isEqualTo(feed.rows("shapes.txt"));
        assertThat(count("dw.gtfs_calendar", version)).isEqualTo(feed.rows("calendar.txt"));
        assertThat(count("dw.route_headway", version)).isPositive();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM dw.dim_vehicle WHERE source = 'FEED' AND vehicle_id IN ('2050', '2051')",
                        Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                        SELECT publisher_name, valid_from IS NOT NULL AS valid, bbox_min_lat IS NOT NULL AS bbox,
                               raw_object_key
                        FROM dw.gtfs_feed_version WHERE feed_version_id = ?
                        """, version))
                .containsEntry("publisher_name", "Metro Transit / Metropolitan Council")
                .containsEntry("valid", true)
                .containsEntry("bbox", true)
                .containsEntry(
                        "raw_object_key",
                        "raw/gtfs-static/" + execution.getExecutionContext().getString(FeedContext.FEED_HASH) + ".zip");

        JsonNode report = report(version);
        assertThat(report.path("result").asString()).isEqualTo("ACCEPTED");
        assertThat(report.path("errors")).isEmpty();
        assertThat(report.path("row_counts").path("stop_times").asLong()).isEqualTo(feed.rows("stop_times.txt"));
        assertThat(report.path("extra_columns").path("trips.txt").toString()).contains("branch_letter");
        String hash = execution.getExecutionContext().getString(FeedContext.FEED_HASH);
        assertThat(Files.exists(RAW.resolve("gtfs-static/" + hash + ".zip"))).isTrue();
        assertThat(Files.exists(
                        WORK.resolve(Long.toString(execution.getJobInstance().getInstanceId()))))
                .as("the workspace of a finished job is removed")
                .isFalse();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(reference.current())
                        .hasValueSatisfying(r -> assertThat(r.feedVersionId()).isEqualTo(activeVersion())));
    }

    @Test
    void g02TheSameFileAgainIsANoop() throws Exception {
        Path zip = FeedBuilder.mini().write(FEEDS);
        assertThat(load(zip).getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        long versions = jdbc.queryForObject("SELECT count(*) FROM dw.gtfs_feed_version", Long.class);

        JobExecution again = load(zip);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(again.getExitStatus().getExitCode()).isEqualTo("NOOP");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dw.gtfs_feed_version", Long.class))
                .isEqualTo(versions);
    }

    private static UnaryOperator<String> lines(UnaryOperator<List<String>> change) {
        return text ->
                String.join("\n", change.apply(new ArrayList<>(text.lines().toList()))) + "\n";
    }

    private static UnaryOperator<String> column(int index, String value) {
        return lines(all -> {
            for (int i = 1; i < all.size(); i++) {
                String[] cells = all.get(i).split(",", -1);
                cells[index] = value;
                all.set(i, String.join(",", cells));
            }
            return all;
        });
    }

    static Stream<Arguments> brokenFeeds() {
        return Stream.of(
                Arguments.of("missing stop_times.txt", "GV-01", (UnaryOperator<FeedBuilder>)
                        f -> f.remove("stop_times.txt")),
                Arguments.of("missing trip_id column", "GV-02", (UnaryOperator<FeedBuilder>)
                        f -> f.edit("trips.txt", t -> t.replaceFirst(",trip_id,", ",trip_ref,"))),
                Arguments.of("two time zones", "GV-03", (UnaryOperator<FeedBuilder>)
                        f -> f.edit("agency.txt", t -> t + "1,Other,https://example.org,America/New_York,en,,,\n")),
                Arguments.of("trip on an unknown route", "GV-04", (UnaryOperator<FeedBuilder>)
                        f -> f.edit("trips.txt", t -> t.replace("901,7,885556,", "999,7,885556,"))),
                Arguments.of("duplicate stop_id", "GV-04", (UnaryOperator<FeedBuilder>) f ->
                        f.edit("stops.txt", lines(all -> {
                            all.add(all.get(1));
                            return all;
                        }))),
                Arguments.of("time 25:61:00", "GV-04", (UnaryOperator<FeedBuilder>) f -> f.edit(
                        "stop_times.txt", t -> t.replace("885556,16:28:00,16:28:00,", "885556,25:61:00,25:61:00,"))),
                Arguments.of("trip with one stop", "GV-06", (UnaryOperator<FeedBuilder>) f ->
                        f.edit("stop_times.txt", lines(all -> {
                            all.removeIf(l -> l.startsWith("885556,") && !l.contains(",51405,1,"));
                            return all;
                        }))),
                Arguments.of("time going backwards", "GV-07", (UnaryOperator<FeedBuilder>) f -> f.edit(
                        "stop_times.txt",
                        t -> t.replace("885556,16:46:00,16:46:00,51431,9,", "885556,16:20:00,16:20:00,51431,9,"))),
                Arguments.of("service_id without calendar", "GV-08", (UnaryOperator<FeedBuilder>)
                        f -> f.edit("trips.txt", t -> t.replace("901,7,885556,", "901,NOPE,885556,"))),
                Arguments.of("no trip on Mondays", "GV-10", (UnaryOperator<FeedBuilder>)
                        f -> f.edit("calendar.txt", column(1, "0"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenFeeds")
    void g04ABrokenFeedIsRejectedAndTheActiveOneStays(String name, String check, UnaryOperator<FeedBuilder> breakIt)
            throws Exception {
        ensureActive();
        long active = activeVersion();
        Path zip = breakIt.apply(FeedBuilder.mini()).write(FEEDS);

        JobExecution execution = load(zip);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("REJECTED");
        long version = versionByHash(zip);
        assertThat(status(version)).isEqualTo("REJECTED");
        JsonNode report = report(version);
        assertThat(report.path("result").asString()).isEqualTo("REJECTED");
        List<String> checks = new ArrayList<>();
        report.path("errors").forEach(e -> checks.add(e.path("check").asString()));
        assertThat(checks).as(report::toString).contains(check);
        assertThat(activeVersion()).isEqualTo(active);
    }

    @Test
    void g05ZipSlipAndZipBombsAreRejected() throws Exception {
        Path slip = FeedBuilder.mini()
                .add("../../escaped.txt", "owned".getBytes(StandardCharsets.UTF_8))
                .write(FEEDS);
        byte[] zeros = new byte[20 * 1024 * 1024];
        Path bomb = FeedBuilder.mini().add("shapes.txt", zeros).write(FEEDS);

        for (Path zip : List.of(slip, bomb)) {
            JobExecution execution = load(zip);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("REJECTED");
            JsonNode report = report(versionByHash(zip));
            assertThat(report.path("errors").get(0).path("check").asString()).isEqualTo("GV-01");
        }
        // ../../escaped.txt from <work>/<instance>/feed/ would land in the work directory itself.
        assertThat(WORK.resolve("escaped.txt")).doesNotExist();
        try (Stream<Path> files = Files.walk(WORK)) {
            assertThat(files.map(p -> p.getFileName().toString())).doesNotContain("escaped.txt");
        }
    }

    @Test
    void g06AFailureInTheMiddleOfStopTimesRestartsFromTheNextChunk() throws Exception {
        FeedBuilder feed = FeedBuilder.mini();
        faults.arm(FaultPoint.BEFORE_WRITE, FaultAction.THROW_FATAL, 1, 1);
        JobExecution failed = load(feed.write(FEEDS));
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        long version = versionOf(failed);
        assertThat(count("dw.gtfs_stop_time", version)).isEqualTo(1000);

        JobExecution restarted = awaitEnd(jobOperator.restart(failed));

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution stopTimes = restarted.getStepExecutions().stream()
                .filter(s -> s.getStepName().equals("loadStopTimes"))
                .findFirst()
                .orElseThrow();
        assertThat(stopTimes.getReadCount())
                .as("continues after the committed chunk")
                .isEqualTo(feed.rows("stop_times.txt") - 1000);
        assertThat(count("dw.gtfs_stop_time", version)).isEqualTo(feed.rows("stop_times.txt"));
        assertThat(status(version)).isEqualTo("ACTIVE");
    }

    @Test
    void g07AMissingWorkspaceIsDownloadedAgainFromTheRawZone() throws Exception {
        faults.arm(FaultPoint.BEFORE_WRITE, FaultAction.THROW_FATAL, 0, 1);
        JobExecution failed = load(FeedBuilder.mini().write(FEEDS));
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        FeedWorkspace.deleteTree(
                WORK.resolve(Long.toString(failed.getJobInstance().getInstanceId())));

        JobExecution restarted = awaitEnd(jobOperator.restart(failed));

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(status(versionOf(failed))).isEqualTo("ACTIVE");
    }

    @Test
    void g08SwappingFeedsKeepsExactlyOneActiveAndAllowsReactivation() throws Exception {
        Path a = FeedBuilder.mini().write(FEEDS);
        assertThat(load(a).getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        long versionA = versionByHash(a);

        AtomicBoolean loading = new AtomicBoolean(true);
        List<Long> samples = new java.util.concurrent.CopyOnWriteArrayList<>();
        JdbcTemplate reader = new JdbcTemplate(new SingleConnectionDataSource(
                MigratedDatabases.jdbcUrl("pti_warehouse"),
                "api_reader",
                MigratedDatabases.password("api_reader"),
                true));
        CompletableFuture<?> watcher = CompletableFuture.runAsync(() -> {
            while (loading.get()) {
                samples.add(reader.queryForObject(
                        "SELECT count(*) FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'", Long.class));
            }
        });
        Path b = FeedBuilder.mini().write(FEEDS);
        JobExecution loadB = load(b);
        loading.set(false);
        watcher.join();

        assertThat(loadB.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(status(versionA)).isEqualTo("RETIRED");
        assertThat(status(versionByHash(b))).isEqualTo("ACTIVE");
        assertThat(samples).isNotEmpty().containsOnly(1L);

        JobExecution noop = load(a);
        assertThat(noop.getExitStatus().getExitCode())
                .as("RETIRED without allowReactivate")
                .isEqualTo("NOOP");
        JobExecution reactivated = awaitEnd(launcher.start(PtiJob.GTFS_STATIC_LOAD, parameters(a, true)));
        assertThat(reactivated.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(status(versionA)).isEqualTo("ACTIVE");
        assertThat(status(versionByHash(b))).isEqualTo("RETIRED");
    }

    @Test
    void g09OnlyTheNewestThreeVersionsAreKept() throws Exception {
        List<Path> zips = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Path zip = FeedBuilder.mini().write(FEEDS);
            assertThat(load(zip).getExitStatus().getExitCode()).isEqualTo("COMPLETED");
            zips.add(zip);
        }

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM dw.gtfs_feed_version WHERE status IN ('ACTIVE', 'RETIRED')", Long.class))
                .isEqualTo(3);
        String oldest = FeedSource.sha256(zips.getFirst());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM dw.gtfs_feed_version WHERE feed_hash = ?", Long.class, oldest))
                .isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM dw.gtfs_stop_time st
                        WHERE NOT EXISTS (SELECT 1 FROM dw.gtfs_feed_version v WHERE v.feed_version_id = st.feed_version_id)
                        """, Long.class))
                .as("no rows of a deleted version remain")
                .isZero();
        assertThat(Files.exists(RAW.resolve("gtfs-static/" + oldest + ".zip"))).isTrue();
    }

    @Test
    void g10TwoLoadsAtOnceRunOneAfterTheOther() throws Exception {
        JobExecution first = launcher.start(
                PtiJob.GTFS_STATIC_LOAD, parameters(FeedBuilder.mini().write(FEEDS), false));
        JobExecution second = launcher.start(
                PtiJob.GTFS_STATIC_LOAD, parameters(FeedBuilder.mini().write(FEEDS), false));
        first = awaitEnd(first);
        second = awaitEnd(second);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(second.getExitStatus().getExitDescription()).contains(GtfsJobListener.ANOTHER_RUNNING);
    }

    @Test
    void g13AJobRequestWithAForbiddenSourceIsRejected() {
        JdbcTemplate api = new JdbcTemplate(new SingleConnectionDataSource(
                MigratedDatabases.jdbcUrl("pti_warehouse"),
                "replay_operator",
                MigratedDatabases.password("replay_operator"),
                true));
        UUID id = UUID.randomUUID();
        api.update("""
                INSERT INTO ops.job_request (id, kind, job_name, job_parameters, requested_by)
                VALUES (?, 'RUN', 'GtfsStaticLoadJob', '{"sourceUri": "file:/etc/hosts"}'::jsonb, 'user:test')
                """, id);

        await().atMost(Duration.ofSeconds(30))
                .until(() -> !"PENDING"
                        .equals(api.queryForObject(
                                "SELECT status FROM ops.job_request WHERE id = ?", String.class, id)));
        Map<String, Object> row = api.queryForMap("SELECT status, message FROM ops.job_request WHERE id = ?", id);
        assertThat(row).containsEntry("status", "REJECTED");
        assertThat((String) row.get("message")).contains("outside the allowed directories");
    }
}
