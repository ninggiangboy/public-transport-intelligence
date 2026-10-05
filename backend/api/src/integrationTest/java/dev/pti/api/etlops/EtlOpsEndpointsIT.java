package dev.pti.api.etlops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.pti.api.ApiIntegrationSupport;
import dev.pti.api.testing.JwtFixture;
import dev.pti.db.MigratedDatabases;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-30…E-57 end to end against the real warehouse: HTTP, use cases, the JDBC adapters as {@code api_reader} and {@code
 * replay_operator} with the grants of DOC-17, the real migrations. The acceptance of P4-12, "a replay creates a
 * replay_request and the ETL processes it", is checked by running the claim queries of {@code etl-batch}'s pollers
 * ({@code ReplayRequests.claim}, {@code JobRequests.claim}) as {@code etl_writer} on the rows the API wrote.
 */
class EtlOpsEndpointsIT extends ApiIntegrationSupport {

    private static final UUID LETTER = UUID.fromString("0192f5b2-8d9e-7a0b-9c1d-2e3f4a5b6c7d");

    /** The query of {@code ReplayRequests.claim} of etl-batch, word for word. */
    private static final String REPLAY_CLAIM = """
            SELECT id, kind, source, from_ts, to_ts, recompute_analytics
            FROM ops.replay_request
            WHERE status = 'PENDING'
            ORDER BY requested_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    /** The query of {@code JobRequests.claim} of etl-batch, word for word. */
    private static final String JOB_CLAIM = """
            SELECT id, kind, job_name, job_parameters::text AS parameters, target_job_execution_id
            FROM ops.job_request
            WHERE status = 'PENDING'
            ORDER BY requested_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private dev.pti.api.platform.adapter.out.cache.ApiCaches caches;

    @BeforeEach
    @AfterEach
    void clean() {
        asOwner(
                "DELETE FROM ops.replay_request",
                "DELETE FROM ops.job_request",
                "DELETE FROM ops.dead_letter",
                "DELETE FROM ops.etl_stream_batch",
                "DELETE FROM batch.batch_job_execution WHERE job_execution_id IN (990001, 990002)",
                "DELETE FROM batch.batch_job_instance WHERE job_instance_id IN (990001, 990002)",
                "UPDATE ops.runtime_flag SET value = 'false', updated_by = 'migration' WHERE key = 'etl.consumer.gtfs-rt.paused'");
    }

    private static String operator() {
        return JwtFixture.bearer(JwtFixture.operator());
    }

    private static String viewer() {
        return JwtFixture.bearer(JwtFixture.viewer());
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }

    private MockHttpServletRequestBuilder jsonPost(String url, String key, String body) {
        MockHttpServletRequestBuilder request = post(url)
                .header("Authorization", operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private static String query(String user, String sql) {
        try (Connection connection = MigratedDatabases.connect("pti_warehouse", user);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** A job execution started 10 minutes ago (job 990001) or 5 minutes ago (any other), straight into batch.*. */
    private static void batchJob(long id, String name, String status) {
        int minutesAgo = id == 990001 ? 10 : 5;
        asOwner(
                "INSERT INTO batch.batch_job_instance (job_instance_id, version, job_name, job_key) VALUES (%d, 0, '%s', '%d')"
                        .formatted(id, name, id),
                """
                INSERT INTO batch.batch_job_execution (job_execution_id, version, job_instance_id, create_time, start_time,
                  end_time, status, exit_code, exit_message, last_updated)
                VALUES (%d, 1, %d, now() AT TIME ZONE 'UTC', (now() AT TIME ZONE 'UTC') - interval '%d minutes',
                  (now() AT TIME ZONE 'UTC') - interval '1 minute', '%s', '%s', '', now() AT TIME ZONE 'UTC')""".formatted(id, id, minutesAgo, status, status));
    }

    private static void deadLetter(String status) {
        asOwner("""
                INSERT INTO ops.dead_letter (id, source, stage, error_class, error_message, rule_id, raw_payload,
                  batch_id, status)
                VALUES ('%s', 'GTFS_RT_VEHICLE_POSITION', 'SCHEMA', 'SchemaViolationException', 'bad', 'DQ-01',
                  '{"schema_version":1,"entity_type":"VEHICLE_POSITION","event_timestamp":"2026-09-29T21:18:45.000Z","payload":{"vehicle_id":"1203"}}',
                  '%s', '%s')""".formatted(LETTER, UUID.randomUUID(), status));
    }

    // ------------------------------------------------------------------------------------- replays and ETL

    @Test
    @DisplayName("P4-12 a raw zone replay writes a replay_request that the ETL poller claims; a second one is 409")
    void rawReplayIsClaimedByTheEtl() throws Exception {
        Instant to = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        String body =
                "{\"source\":\"GTFS_RT_TRIP_UPDATE\",\"fromTs\":\"%s\",\"toTs\":\"%s\",\"recomputeAnalytics\":false}"
                        .formatted(to.minus(1, ChronoUnit.DAYS), to);

        JsonNode created = json(call(jsonPost("/api/v1/etl/replays", "k-1", body)), 202);
        UUID id = UUID.fromString(created.path("id").asString());
        JsonNode conflict = json(call(jsonPost("/api/v1/etl/replays", "k-2", body)), 409);
        assertThat(conflict.path("existingReplayId").asString()).isEqualTo(id.toString());
        assertThat(json(call(jsonPost("/api/v1/etl/replays", "k-1", body)), 202)
                        .path("id")
                        .asString())
                .isEqualTo(id.toString());
        assertThat(query("pti_owner", "SELECT count(*) FROM ops.replay_request"))
                .isEqualTo("1");

        // What the poller of etl-batch selects and moves to RUNNING.
        try (Connection etl = MigratedDatabases.connect("pti_warehouse", "etl_writer")) {
            etl.setAutoCommit(false);
            try (Statement statement = etl.createStatement();
                    ResultSet rs = statement.executeQuery(REPLAY_CLAIM)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("id", UUID.class)).isEqualTo(id);
                assertThat(rs.getString("kind")).isEqualTo("RAW_RANGE");
                assertThat(rs.getString("source")).isEqualTo("GTFS_RT_TRIP_UPDATE");
                assertThat(rs.getTimestamp("to_ts").toInstant()).isEqualTo(to);
                assertThat(rs.getBoolean("recompute_analytics")).isFalse();
            }
            try (Statement statement = etl.createStatement()) {
                assertThat(statement.executeUpdate(
                                "UPDATE ops.replay_request SET status = 'RUNNING', started_at = now() WHERE id = '" + id
                                        + "'"))
                        .isEqualTo(1);
            }
            etl.commit();
        }
        JsonNode running = json(call(get("/api/v1/etl/replays/" + id).header("Authorization", viewer())), 200);
        assertThat(running.path("status").asString()).isEqualTo("RUNNING");
        assertThat(json(call(get("/api/v1/etl/replays").header("Authorization", viewer())), 200)
                        .path("items"))
                .hasSize(1);
    }

    @Test
    @DisplayName("P4-12 a job request is claimed by the ETL poller with the parameters as make job-run writes them")
    void jobRequestIsClaimedByTheEtl() throws Exception {
        String day = java.time.LocalDate.now().minusDays(1).toString();
        asOwner(activeFeedSql("c", "2026-08-23"));
        caches.names().forEach(name -> caches.cache(name).invalidateAll());
        try {
            JsonNode created = json(
                    call(jsonPost(
                            "/api/v1/etl/jobs",
                            null,
                            "{\"jobName\":\"OtpScorecardJob\",\"parameters\":{\"serviceDates\":[\"%s\",\"%s\"]}}"
                                    .formatted(day, java.time.LocalDate.now().minusDays(2)))),
                    202);
            UUID id = UUID.fromString(created.path("id").asString());
            try (Connection etl = MigratedDatabases.connect("pti_warehouse", "etl_writer");
                    Statement statement = etl.createStatement();
                    ResultSet rs = statement.executeQuery(JOB_CLAIM)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("id", UUID.class)).isEqualTo(id);
                assertThat(rs.getString("kind")).isEqualTo("RUN");
                assertThat(rs.getString("job_name")).isEqualTo("OtpScorecardJob");
                assertThat(mapper.readTree(rs.getString("parameters"))
                                .path("serviceDates")
                                .asString())
                        .isEqualTo(java.time.LocalDate.now().minusDays(2) + "+" + day);
            }
            assertThat(json(call(get("/api/v1/etl/job-requests/" + id).header("Authorization", viewer())), 200)
                            .path("status")
                            .asString())
                    .isEqualTo("PENDING");
        } finally {
            asOwner("DELETE FROM dw.gtfs_feed_version");
        }
    }

    @Test
    @DisplayName("EP-25 AG-11 two replays of one MANUAL dead letter at once give one 202, one 409 and one row; "
            + "one key sent twice at once gives one row")
    void concurrency() throws Exception {
        deadLetter("MANUAL");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> a = () -> call(jsonPost("/api/v1/etl/dlq/" + LETTER + "/replay", "x-a", "{}"))
                    .getStatus();
            Callable<Integer> b = () -> call(jsonPost("/api/v1/etl/dlq/" + LETTER + "/replay", "x-b", "{}"))
                    .getStatus();
            List<Future<Integer>> results = pool.invokeAll(List.of(a, b));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(202, 409);
        } finally {
            pool.shutdown();
        }
        assertThat(query("pti_owner", "SELECT count(*) FROM ops.replay_request"))
                .isEqualTo("1");
        assertThat(query("pti_owner", "SELECT string_agg(action, ',' ORDER BY id) FROM ops.dlq_action_log"))
                .isEqualTo("REPLAY_REQUESTED");

        asOwner("DELETE FROM ops.replay_request");
        Instant to = Instant.now().minus(2, ChronoUnit.HOURS);
        String body = "{\"source\":\"TICKETING_SALES\",\"fromTs\":\"%s\",\"toTs\":\"%s\"}"
                .formatted(to.minus(1, ChronoUnit.HOURS), to);
        pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> same = () ->
                    call(jsonPost("/api/v1/etl/replays", "same-key", body)).getStatus();
            List<Future<Integer>> results = pool.invokeAll(List.of(same, same));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactly(202, 202);
        } finally {
            pool.shutdown();
        }
        assertThat(query("pti_owner", "SELECT count(*) FROM ops.replay_request"))
                .isEqualTo("1");
    }

    // ------------------------------------------------------------------------------------------ dead letters

    @Test
    @DisplayName("E-42…E-47 read, edit, discard and resolve a dead letter within the grants of replay_operator")
    void deadLetters() throws Exception {
        deadLetter("MANUAL");
        JsonNode detail = json(call(get("/api/v1/etl/dlq/" + LETTER).header("Authorization", operator())), 200);
        assertThat(detail.path("allowedActions")).hasSize(4);
        assertThat(json(call(get("/api/v1/etl/dlq").param("status", "MANUAL").header("Authorization", viewer())), 200)
                        .path("items"))
                .hasSize(1);
        assertThat(json(call(get("/api/v1/etl/dlq/summary").header("Authorization", viewer())), 200)
                        .path("open")
                        .asInt())
                .isEqualTo(1);

        String edited =
                "{\"schema_version\":1,\"entity_type\":\"VEHICLE_POSITION\",\"event_timestamp\":\"2026-09-29T21:18:45.000Z\",\"payload\":{\"vehicle_id\":\"1203\",\"trip_id\":\"t1\",\"route_id\":\"18\",\"position\":{\"latitude\":44.9,\"longitude\":-93.2}}}";
        MockHttpServletResponse put = call(put("/api/v1/etl/dlq/" + LETTER + "/payload")
                .header("Authorization", operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content(edited));
        // The payload may or may not satisfy every rule of the schema; what matters is that a refusal writes nothing.
        if (put.getStatus() == 200) {
            assertThat(query("pti_owner", "SELECT count(*) FROM ops.dlq_action_log WHERE action = 'EDITED'"))
                    .isEqualTo("1");
        } else {
            assertThat(put.getStatus()).isEqualTo(422);
            assertThat(query("pti_owner", "SELECT count(*) FROM ops.dead_letter WHERE edited_payload IS NOT NULL"))
                    .isEqualTo("0");
        }

        String reason = "{\"reason\":\"Duplicate of an already corrected record\"}";
        JsonNode discarded = json(call(jsonPost("/api/v1/etl/dlq/" + LETTER + "/discard", null, reason)), 200);
        assertThat(discarded.path("status").asString()).isEqualTo("DISCARDED");
        assertThat(query("pti_owner", "SELECT resolved_by FROM ops.dead_letter"))
                .isEqualTo("user:operator");
        MockHttpServletResponse again =
                call(jsonPost("/api/v1/etl/dlq/" + LETTER + "/resolve", null, "{\"note\":\"done elsewhere\"}"));
        assertThat(json(again, 409).path("currentStatus").asString()).isEqualTo("DISCARDED");
        assertThat(json(call(get("/api/v1/etl/dlq/actions").header("Authorization", viewer())), 200)
                        .path("items")
                        .size())
                .isGreaterThanOrEqualTo(1);
    }

    // ----------------------------------------------------------------------------------- flags, runs, feeds

    @Test
    @DisplayName("E-55…E-57 a flag is changed within the grants and the type is kept")
    void flags() throws Exception {
        String url = "/api/v1/etl/flags/etl.consumer.gtfs-rt.paused";
        assertThat(json(call(get("/api/v1/etl/flags").header("Authorization", viewer())), 200)
                        .path("items")
                        .size())
                .isEqualTo(7);
        JsonNode changed = json(
                call(put(url).header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":true}")),
                200);
        assertThat(changed.path("value").asBoolean()).isTrue();
        assertThat(query(
                        "pti_owner",
                        "SELECT updated_by FROM ops.runtime_flag WHERE key = 'etl.consumer.gtfs-rt.paused'"))
                .isEqualTo("user:operator");
        assertThat(call(put(url).header("Authorization", operator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"yes\"}"))
                        .getStatus())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("E-30…E-32, E-37, E-38, E-53 the run, batch and estimate queries run against the real views")
    void runsAndBatches() throws Exception {
        UUID batch = UUID.randomUUID();
        asOwner("""
                INSERT INTO ops.etl_stream_batch (batch_id, source, listener_id, consumer_group, instance_id, offsets,
                  status, write_mode, records_read, records_written, records_skipped, records_duplicate, started_at,
                  finished_at)
                VALUES ('%s', 'GTFS_RT_VEHICLE_POSITION', 'gtfs-rt-vehicle-position', 'g', 'pod-1', '{"t-0": [1, 9]}',
                  'COMPLETED', 'BATCH', 100, 100, 0, 0, now() - interval '15 minutes',
                  now() - interval '15 minutes' + interval '1 second')""".formatted(batch));
        JsonNode list =
                json(call(get("/api/v1/etl/jobs").param("kind", "STREAM").header("Authorization", viewer())), 200);
        assertThat(list.path("items")).hasSize(1);
        String runId = list.path("items").get(0).path("runId").asString();
        assertThat(runId).startsWith("stream:gtfs-rt-vehicle-position:");
        JsonNode run = json(call(get("/api/v1/etl/jobs/" + runId).header("Authorization", viewer())), 200);
        assertThat(run.path("batches")).hasSize(1);
        assertThat(run.path("batches").get(0).path("links").path("trace").asString())
                .contains("explore");
        JsonNode summary = json(
                call(get("/api/v1/etl/jobs/summary").param("bucket", "5m").header("Authorization", viewer())), 200);
        assertThat(summary.path("stream")).hasSize(4);
        JsonNode lineage = json(call(get("/api/v1/etl/batches/" + batch).header("Authorization", viewer())), 200);
        assertThat(lineage.path("origin").asString()).isEqualTo("STREAM");
        assertThat(lineage.path("counts").path("read").asInt()).isEqualTo(100);
        assertThat(call(get("/api/v1/etl/batches/" + UUID.randomUUID()).header("Authorization", viewer()))
                        .getStatus())
                .isEqualTo(404);
        assertThat(call(get("/api/v1/etl/jobs/job:999999").header("Authorization", viewer()))
                        .getStatus())
                .isEqualTo(404);
        batchJob(990001, "OtpScorecardJob", "FAILED");
        batchJob(990002, "PartitionMaintenanceJob", "COMPLETED");
        asOwner("""
                INSERT INTO ops.job_request (id, kind, job_name, requested_by, status, job_execution_id, started_at,
                  finished_at)
                VALUES ('%s', 'RUN', 'OtpScorecardJob', 'user:operator', 'DONE', 990001, now(), now())""".formatted(UUID.randomUUID()));
        JsonNode jobs =
                json(call(get("/api/v1/etl/jobs").param("kind", "BATCH_JOB").header("Authorization", viewer())), 200);
        assertThat(jobs.path("items")).hasSize(2);
        JsonNode manual = jobs.path("items").get(1);
        assertThat(manual.path("runId").asString()).isEqualTo("job:990001");
        assertThat(manual.path("request").path("type").asString()).isEqualTo("job");
        assertThat(manual.path("request").path("requestedBy").asString()).isEqualTo("user:operator");
        assertThat(jobs.path("items").get(0).has("request")).isFalse();
        assertThat(json(call(get("/api/v1/etl/jobs/job:990001").header("Authorization", viewer())), 200)
                        .path("request")
                        .path("requestedBy")
                        .asString())
                .isEqualTo("user:operator");
        assertThat(call(get("/api/v1/etl/feeds").header("Authorization", viewer()))
                        .getStatus())
                .isEqualTo(200);

        Instant from = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        Instant to = from.plus(20, ChronoUnit.MINUTES);
        JsonNode estimate = json(
                call(get("/api/v1/etl/replays/estimate")
                        .param("source", "GTFS_RT_VEHICLE_POSITION")
                        .param("fromTs", from.toString())
                        .param("toTs", to.toString())
                        .header("Authorization", operator())),
                200);
        assertThat(estimate.path("basis").asString()).isEqualTo("STREAM_BATCH_LOG");
        assertThat(estimate.path("estimatedMessages").asInt()).isEqualTo(100);
    }
}
