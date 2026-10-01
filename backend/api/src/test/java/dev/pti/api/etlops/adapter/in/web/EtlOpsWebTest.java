package dev.pti.api.etlops.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterItem;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.api.etlops.domain.JobRun;
import dev.pti.api.etlops.domain.ReplayEstimate;
import dev.pti.api.etlops.domain.RunKind;
import dev.pti.api.etlops.domain.RuntimeFlag;
import dev.pti.api.testing.JwtFixture;
import dev.pti.apitest.ApiWebTestSupport;
import dev.pti.apitest.InMemoryEtlOps;
import dev.pti.apitest.RecordingUiEvents;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * E-30…E-57 through HTTP over the in-memory ports (DOC-32 §13: EP-17…EP-31, AG-09, AG-10): the real controllers and use
 * cases, with the rules of the SQL in {@link InMemoryEtlOps}.
 */
@ResourceLock("in-memory-etlops")
@ResourceLock("in-memory-alerts")
@ResourceLock("in-memory-insight")
class EtlOpsWebTest extends ApiWebTestSupport {

    private static final UUID LETTER = UUID.fromString("0192f5b2-8d9e-7a0b-9c1d-2e3f4a5b6c7d");

    @Autowired
    private InMemoryEtlOps etl;

    @Autowired
    private RecordingUiEvents events;

    @Autowired
    private BusinessClock clock;

    @BeforeEach
    void empty() {
        etl.reset();
        events.clear();
    }

    private static String viewer() {
        return JwtFixture.bearer(JwtFixture.viewer());
    }

    private static String operator() {
        return JwtFixture.bearer(JwtFixture.operator());
    }

    private void letter(DeadLetterStatus status, String source) {
        DeadLetterItem item = new DeadLetterItem(
                LETTER,
                source,
                "SCHEMA",
                "DQ-01",
                "SchemaViolationException",
                "bad",
                status,
                "schema_violation",
                null,
                2,
                null,
                "1203|2026-09-29T21:18:45Z",
                "{}",
                false,
                0,
                0,
                Instant.parse("2026-09-29T21:18:47Z"),
                Instant.parse("2026-09-29T21:19:02Z"));
        String raw =
                "{\"schema_version\":1,\"entity_type\":\"VEHICLE_POSITION\",\"event_timestamp\":\"2026-09-29T21:18:45Z\",\"payload\":{\"vehicle_id\":\"1203\"}}";
        etl.letters.put(
                LETTER,
                new DeadLetterDetail(
                        item,
                        raw,
                        null,
                        null,
                        UUID.randomUUID(),
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()));
    }

    private JobRun run(String id, RunKind kind, String name, String status, Long execution) {
        Instant started = clock.realNow().minusSeconds(600);
        return new JobRun(
                id,
                kind,
                name,
                status,
                null,
                null,
                started,
                started.plusSeconds(60),
                10,
                9,
                1,
                null,
                execution,
                List.of(),
                0,
                null);
    }

    // ----------------------------------------------------------------------------------------------- jobs

    @Test
    @DisplayName("EP-17 E-30 a window of 25 hours is 400; kind=STREAM lists only stream runs; restartable is set")
    void jobRuns() throws Exception {
        etl.runs.add(run("job:1", RunKind.BATCH_JOB, "PartitionMaintenanceJob", "FAILED", 1L));
        etl.runs.add(run(
                "stream:gtfs-rt-vehicle-position:2026-09-29T21:18Z",
                RunKind.STREAM,
                "gtfs-rt-vehicle-position",
                "COMPLETED",
                null));

        mvc.perform(get("/api/v1/etl/jobs").param("from", "-25h").header("Authorization", viewer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        mvc.perform(get("/api/v1/etl/jobs").param("kind", "STREAM").header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].batchCount").value(0));
        mvc.perform(get("/api/v1/etl/jobs").header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.runId=='job:1')].restartable").value(true));
        mvc.perform(get("/api/v1/etl/jobs").param("bogus", "1").header("Authorization", viewer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("EP-18 E-32 a run id of the wrong shape and one that does not exist are both 404")
    void runNotFound() throws Exception {
        mvc.perform(get("/api/v1/etl/jobs/bad").header("Authorization", viewer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/etl/jobs/job:999999").header("Authorization", viewer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("E-31 the summary has every source and the empty buckets of the window")
    void summary() throws Exception {
        mvc.perform(get("/api/v1/etl/jobs/summary").param("bucket", "15m").header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stream.length()").value(4))
                .andExpect(
                        jsonPath("$.stream[0].points.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)));
        mvc.perform(get("/api/v1/etl/jobs/summary").param("bucket", "2m").header("Authorization", viewer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName(
            "EP-19 E-33 a job that cannot be run by hand is 422; a service date of today is 400; a valid one is 202")
    void requestJob() throws Exception {
        mvc.perform(post("/api/v1/etl/jobs")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"DedupRegistryCleanupJob\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:job-not-allowed"));
        mvc.perform(post("/api/v1/etl/jobs")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"EtaAggregationJob\",\"parameters\":{\"nope\":1}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/etl/jobs")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"EtaAggregationJob\",\"parameters\":{\"force\":true}}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/etl/job-requests/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.parameters.force").value("true"))
                .andExpect(jsonPath("$.requestedBy").value("user:operator"));
        assertThat(etl.jobRequests).hasSize(1);
    }

    @Test
    @DisplayName(
            "AG-09, AG-10 the same Idempotency-Key and body gives the same request and one row; another body is 422")
    void idempotentJob() throws Exception {
        String body = "{\"jobName\":\"PartitionMaintenanceJob\"}";
        String first = mvc.perform(post("/api/v1/etl/jobs")
                        .header("Authorization", operator())
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String second = mvc.perform(post("/api/v1/etl/jobs")
                        .header("Authorization", operator())
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(etl.jobRequests).hasSize(1);
        mvc.perform(post("/api/v1/etl/jobs")
                        .header("Authorization", operator())
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobName\":\"EtaAggregationJob\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:idempotency-key-reused"));
    }

    @Test
    @DisplayName("EP-21 E-35, E-36 restart of a COMPLETED run and of a stream run is 409; stop needs a running one")
    void restartAndStop() throws Exception {
        etl.runs.add(run("job:7", RunKind.BATCH_JOB, "PartitionMaintenanceJob", "COMPLETED", 7L));
        etl.runs.add(run("job:8", RunKind.BATCH_JOB, "PartitionMaintenanceJob", "FAILED", 8L));
        etl.runs.add(run(
                "stream:gtfs-rt-trip-update:2026-09-29T21:18Z", RunKind.STREAM, "gtfs-rt-trip-update", "FAILED", null));

        mvc.perform(post("/api/v1/etl/jobs/job:7/restart").header("Authorization", operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:job-not-restartable"));
        mvc.perform(post("/api/v1/etl/jobs/stream:gtfs-rt-trip-update:2026-09-29T21:18Z/restart")
                        .header("Authorization", operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Streaming runs cannot be restarted."));
        mvc.perform(post("/api/v1/etl/jobs/job:8/restart").header("Authorization", operator()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("RESTART"))
                .andExpect(jsonPath("$.targetRunId").value("job:8"));
        mvc.perform(post("/api/v1/etl/jobs/job:8/stop").header("Authorization", operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:job-not-running"));
        mvc.perform(post("/api/v1/etl/jobs/job:99/restart").header("Authorization", operator()))
                .andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------------------------------------- dead letters

    @Test
    @DisplayName("EP-24 E-44 on a REPLAYED dead letter is 409 dlq-invalid-state with currentStatus")
    void replayOfReplayed() throws Exception {
        letter(DeadLetterStatus.REPLAYED, "GTFS_RT_VEHICLE_POSITION");
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/replay").header("Authorization", operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:dlq-invalid-state"))
                .andExpect(jsonPath("$.currentStatus").value("REPLAYED"));
        assertThat(etl.replays).isEmpty();
    }

    @Test
    @DisplayName(
            "E-44 a MANUAL dead letter is replayed once: 202 with a replay request, then 409; the log and event follow")
    void replay() throws Exception {
        letter(DeadLetterStatus.MANUAL, "GTFS_RT_VEHICLE_POSITION");
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/replay")
                        .header("Authorization", operator())
                        .header("Idempotency-Key", "r-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("DLQ_RECORD"))
                .andExpect(jsonPath("$.deadLetterId").value(LETTER.toString()));
        // The same key and the same ask: the first answer, nothing written.
        int writes = etl.statements.size();
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/replay")
                        .header("Authorization", operator())
                        .header("Idempotency-Key", "r-1"))
                .andExpect(status().isAccepted());
        assertThat(etl.statements).hasSize(writes);
        // Another key: the dead letter is no longer MANUAL.
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/replay")
                        .header("Authorization", operator())
                        .header("Idempotency-Key", "r-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("REPLAY_REQUESTED"));
        assertThat(etl.replays).hasSize(1);
        assertThat(etl.actionLog).extracting(a -> a.action()).containsExactly("REPLAY_REQUESTED");
        assertThat(events.events()).hasSize(1);
        assertThat(events.events().get(0).type()).isEqualTo("dlq.changed");
        assertThat(events.events().get(0).data()).containsEntry("previousStatus", "MANUAL");
    }

    @Test
    @DisplayName("EP-26 E-45 confirm logs CONFIRMED and then REPLAY_REQUESTED")
    void confirm() throws Exception {
        letter(DeadLetterStatus.PENDING_CONFIRM, "GTFS_RT_VEHICLE_POSITION");
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/confirm").header("Authorization", operator()))
                .andExpect(status().isAccepted());
        assertThat(etl.actionLog).extracting(a -> a.action()).containsExactly("CONFIRMED", "REPLAY_REQUESTED");
    }

    @Test
    @DisplayName(
            "EP-27 E-46 a discard without a reason is 400; with one it is 200, and again by the same person changes nothing")
    void discard() throws Exception {
        letter(DeadLetterStatus.NEW, "GTFS_RT_VEHICLE_POSITION");
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/discard")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        String body = "{\"reason\":\"Duplicate of an already corrected record\"}";
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/discard")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"))
                .andExpect(jsonPath("$.resolvedBy").value("user:operator"));
        int writes = etl.statements.size();
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/discard")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        assertThat(etl.statements).hasSize(writes);
        mvc.perform(post("/api/v1/etl/dlq/" + LETTER + "/resolve")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Handled in the source\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("EP-23 E-43 an edit is refused with the right code and leaves edited_payload alone")
    void editPayload() throws Exception {
        letter(DeadLetterStatus.MANUAL, "GTFS_RT_VEHICLE_POSITION");
        String url = "/api/v1/etl/dlq/" + LETTER + "/payload";
        mvc.perform(put(url).header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{nope"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:invalid-payload"));
        mvc.perform(put(url).header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer_ref\":\"x\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:pii-not-allowed"));
        mvc.perform(
                        put(url).header("Authorization", operator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"schema_version\":1,\"entity_type\":\"VEHICLE_POSITION\",\"event_timestamp\":\"2026-09-29T21:18:45.000Z\",\"payload\":{\"vehicle_id\":\"9999\"}}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(etl.letters.get(LETTER).editedPayload()).isNull();
        assertThat(etl.statements).isEmpty();
    }

    @Test
    @DisplayName("E-42 allowedActions follows the status and the role; a viewer gets none")
    void allowedActions() throws Exception {
        letter(DeadLetterStatus.MANUAL, "GTFS_RT_VEHICLE_POSITION");
        mvc.perform(get("/api/v1/etl/dlq/" + LETTER).header("Authorization", operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions")
                        .value(org.hamcrest.Matchers.contains("edit", "replay", "discard", "resolve")));
        mvc.perform(get("/api/v1/etl/dlq/" + LETTER).header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions").isEmpty());
        mvc.perform(get("/api/v1/etl/dlq/not-a-uuid").header("Authorization", viewer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/etl/dlq/summary").header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.byStatus.MANUAL").value(1));
        mvc.perform(get("/api/v1/etl/dlq").param("status", "NEW").header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ---------------------------------------------------------------------------------------------- replays

    private static String window(String source, Instant from, Instant to) {
        return "{\"source\":\"%s\",\"fromTs\":\"%s\",\"toTs\":\"%s\",\"recomputeAnalytics\":true}"
                .formatted(source, from, to);
    }

    @Test
    @DisplayName("EP-28 E-50 a second replay of the source is 409 with the id of the first")
    void rawReplayConflict() throws Exception {
        Instant to = clock.realNow().minus(Duration.ofHours(1));
        String body = window("GTFS_RT_TRIP_UPDATE", to.minus(Duration.ofHours(24)), to);
        String id = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post("/api/v1/etl/replays")
                                .header("Authorization", operator())
                                .header("Idempotency-Key", "a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.kind").value("RAW_RANGE"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mvc.perform(post("/api/v1/etl/replays")
                        .header("Authorization", operator())
                        .header("Idempotency-Key", "b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:replay-already-running"))
                .andExpect(jsonPath("$.existingReplayId").value(id));
        // Same key, another window: the key was used for a different request.
        mvc.perform(post("/api/v1/etl/replays")
                        .header("Authorization", operator())
                        .header("Idempotency-Key", "a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(window("GTFS_RT_TRIP_UPDATE", to.minus(Duration.ofHours(48)), to)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:idempotency-key-reused"));
        assertThat(etl.replays).hasSize(1);
    }

    @Test
    @DisplayName("EP-29 E-50 the last 10 minutes, a window of 8 days and GTFS_STATIC are 422")
    void rawReplayWindows() throws Exception {
        Instant now = clock.realNow();
        mvc.perform(post("/api/v1/etl/replays")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(window(
                                "TICKETING_SALES", now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(5)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:replay-window-invalid"));
        mvc.perform(post("/api/v1/etl/replays")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(window(
                                "TICKETING_SALES", now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:replay-window-invalid"));
        mvc.perform(post("/api/v1/etl/replays")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(window("GTFS_STATIC", now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(1)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:unsupported-source"));
        assertThat(etl.replays).isEmpty();
    }

    @Test
    @DisplayName("EP-30 E-53 an estimate with full history and one without")
    void estimate() throws Exception {
        Instant to = clock.realNow().minus(Duration.ofHours(2));
        Instant from = to.minus(Duration.ofHours(1));
        etl.history = new ReplayEstimate.History(180_000, 60, 60);
        mvc.perform(get("/api/v1/etl/replays/estimate")
                        .param("source", "GTFS_RT_TRIP_UPDATE")
                        .param("fromTs", from.toString())
                        .param("toTs", to.toString())
                        .header("Authorization", operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage").value(1.0))
                .andExpect(jsonPath("$.estimatedMessages").value(180000))
                .andExpect(jsonPath("$.estimatedDurationSeconds").value(60));
        etl.history = new ReplayEstimate.History(0, 0, 0);
        mvc.perform(get("/api/v1/etl/replays/estimate")
                        .param("source", "GTFS_RT_TRIP_UPDATE")
                        .param("fromTs", from.toString())
                        .param("toTs", to.toString())
                        .header("Authorization", operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.estimatedMessages").doesNotExist());
        mvc.perform(get("/api/v1/etl/replays/estimate")
                        .param("source", "GTFS_RT_TRIP_UPDATE")
                        .param("fromTs", to.toString())
                        .param("toTs", from.toString())
                        .header("Authorization", operator()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------------ flags

    @Test
    @DisplayName(
            "EP-31 E-57 a string for a boolean flag is 422, an unknown key 404, a boolean 200 (and again changes nothing)")
    void flags() throws Exception {
        etl.flags.put(
                "etl.consumer.gtfs-rt.paused",
                new RuntimeFlag(
                        "etl.consumer.gtfs-rt.paused",
                        false,
                        "Pause.",
                        "migration",
                        Instant.parse("2026-09-27T08:30:00Z")));
        String url = "/api/v1/etl/flags/etl.consumer.gtfs-rt.paused";
        mvc.perform(put(url).header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"yes\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:invalid-flag-value"));
        mvc.perform(put("/api/v1/etl/flags/no.such.flag")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(put(url).header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(true))
                .andExpect(jsonPath("$.updatedBy").value("user:operator"));
        int writes = etl.statements.size();
        mvc.perform(put(url).header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":true}"))
                .andExpect(status().isOk());
        assertThat(etl.statements).hasSize(writes);
        mvc.perform(get("/api/v1/etl/flags").header("Authorization", viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(url).header("Authorization", viewer())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("E-90 outside profile demo the proxy does not exist, even for an operator")
    void simulatorProxyNeedsDemo() throws Exception {
        mvc.perform(get("/api/v1/sim/status").header("Authorization", operator()))
                .andExpect(status().isNotFound());
    }
}
