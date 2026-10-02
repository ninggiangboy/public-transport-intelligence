package dev.pti.etl.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.common.json.MessageJson;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.fault.ConfigurableFaultInjector;
import dev.pti.etl.fault.FaultAction;
import dev.pti.etl.fault.FaultPoint;
import dev.pti.etl.gtfs.LocalRawZone;
import dev.pti.etl.raw.RawZone;
import dev.pti.etl.testing.EtlFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Raw zone and dead-letter replays (DOC-22 §11), with the raw zone as a local directory. */
class ReplayIT extends BatchContextSupport {

    private static final String TOPIC = "ticketing.sales.cdc";

    /** The api writes replay requests and edits dead letters as {@code replay_operator} (DOC-17). */
    private static JdbcTemplate api;

    @Autowired
    RawZone raw;

    @Autowired
    ConfigurableFaultInjector faults;

    @BeforeAll
    static void openApi() {
        api = new JdbcTemplate(new SingleConnectionDataSource(
                MigratedDatabases.jdbcUrl("pti_warehouse"),
                "replay_operator",
                MigratedDatabases.password("replay_operator"),
                true));
    }

    @AfterEach
    void disarm() {
        faults.disarmAll();
    }

    /** An hour of record time nobody else uses, so each test reads only its own objects. */
    private static Instant hour() {
        return Instant.parse("2025-01-01T00:00:00Z")
                .plus(ThreadLocalRandom.current().nextInt(0, 80_000), ChronoUnit.HOURS);
    }

    private static long firstOffset() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 1_000_000_000_000L);
    }

    private static byte[] sale(String transactionId, String amount) {
        ObjectNode sale = EtlFixtures.ticketSale();
        sale.put("transaction_id", transactionId).put("amount", amount);
        return MessageJson.mapper().writeValueAsString(sale).getBytes(StandardCharsets.UTF_8);
    }

    private static String line(long offset, Instant timestamp, byte[] value) {
        ObjectNode node = MessageJson.mapper().createObjectNode();
        node.put("key", "{\"transaction_id\":\"x\"}");
        node.put("value", Base64.getEncoder().encodeToString(value));
        node.put("offset", offset);
        node.put("timestamp", timestamp.toString());
        node.putArray("headers").addObject().put("key", "__op").put("value", "c");
        return MessageJson.mapper().writeValueAsString(node);
    }

    private static String key(Instant hour, int partition, long startOffset) {
        String dir = hour.toString().substring(0, 10) + "/hh=" + hour.toString().substring(11, 13);
        return TOPIC + "/dt=" + dir + "/" + TOPIC + "-" + partition + "-" + String.format("%020d", startOffset)
                + ".json.gz";
    }

    private void object(Instant hour, int partition, long startOffset, List<String> lines) {
        ((LocalRawZone) raw).write(key(hour, partition, startOffset), gzip(String.join("\n", lines) + "\n"));
    }

    private static byte[] gzip(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** {@code n} valid sales at consecutive offsets in one object; returns their transaction ids. */
    private List<String> sales(Instant hour, int partition, long firstOffset, int n) {
        List<String> ids = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            lines.add(line(firstOffset + i, hour.plusSeconds(i % 3600), sale(id, "5.00")));
        }
        object(hour, partition, firstOffset, lines);
        return ids;
    }

    private UUID rawReplay(Instant from, Instant to) {
        return rawReplay(from, to, false);
    }

    private UUID rawReplay(Instant from, Instant to, boolean recomputeAnalytics) {
        UUID id = UUID.randomUUID();
        api.update("""
                INSERT INTO ops.replay_request (id, kind, source, from_ts, to_ts, recompute_analytics, requested_by)
                VALUES (?, 'RAW_RANGE', 'TICKETING_SALES', ?, ?, ?, 'user:test')
                """, id, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), recomputeAnalytics);
        return id;
    }

    private List<String> steps(Map<String, Object> request) {
        return jdbc.queryForList(
                "SELECT step_name FROM batch.batch_step_execution WHERE job_execution_id = ? ORDER BY step_execution_id",
                String.class,
                request.get("job_execution_id"));
    }

    private Map<String, Object> awaitRequest(UUID id) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> List.of("DONE", "FAILED")
                        .contains(api.queryForObject(
                                "SELECT status FROM ops.replay_request WHERE id = ?", String.class, id)));
        return api.queryForMap(
                "SELECT status, message, stats::text AS stats, job_execution_id FROM ops.replay_request WHERE id = ?",
                id);
    }

    private static JsonNode stats(Map<String, Object> request) {
        return MessageJson.mapper().readTree(String.valueOf(request.get("stats")));
    }

    private long facts(List<String> transactionIds) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM dw.fact_ticket_sales WHERE transaction_id::text = ANY (?::text[])",
                Long.class,
                "{" + String.join(",", transactionIds) + "}");
    }

    private List<Map<String, Object>> deadLetters(long fromOffset, long toOffset) {
        return jdbc.queryForList("""
                SELECT id, stage, rule_id, error_class, status, replay_count, kafka_offset FROM ops.dead_letter
                WHERE kafka_topic = ? AND kafka_offset BETWEEN ? AND ? ORDER BY kafka_offset
                """, TOPIC, fromOffset, toOffset);
    }

    @Test
    void r05AndR12AReplayRewritesTheWindowUnderItsOwnBatchId() {
        Instant hour = hour();
        long offset = firstOffset();
        List<String> ids = sales(hour, 0, offset, 40);

        Map<String, Object> first = awaitRequest(rawReplay(hour, hour.plus(1, ChronoUnit.HOURS)));
        assertThat(first).as(first::toString).containsEntry("status", "DONE");
        assertThat(facts(ids)).isEqualTo(40);

        UUID second = rawReplay(hour, hour.plus(1, ChronoUnit.HOURS));
        Map<String, Object> done = awaitRequest(second);

        assertThat(done).containsEntry("status", "DONE");
        assertThat(facts(ids)).as("upserts: no new rows").isEqualTo(40);
        JsonNode stats = stats(done);
        assertThat(stats.path("objects").asLong()).isEqualTo(1);
        assertThat(stats.path("written").asLong()).isEqualTo(40);
        assertThat(stats.path("lines_read").asLong()).isEqualTo(40);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM dw.fact_ticket_sales f
                        JOIN ops.etl_batch_step s ON s.batch_id = f.batch_id
                        JOIN batch.batch_step_execution se ON se.step_execution_id = s.step_execution_id
                        JOIN ops.replay_request r ON r.job_execution_id = se.job_execution_id
                        WHERE r.id = ?
                        """, Long.class, second))
                .as("R-12: every row traces back to the second replay")
                .isEqualTo(40);
    }

    @Test
    void anR12AReplayWithRecomputeAnalyticsRunsTheStepAndReportsItsStats() {
        Instant hour = hour();
        sales(hour, 0, firstOffset(), 3);

        Map<String, Object> done = awaitRequest(rawReplay(hour, hour.plus(1, ChronoUnit.HOURS), true));

        assertThat(done).as(done::toString).containsEntry("status", "DONE");
        assertThat(steps(done)).containsExactly("listObjects", "replayRecords", "recomputeAnalytics");
        JsonNode stats = stats(done);
        assertThat(stats.path("analytics_recomputed").asBoolean()).isTrue();
        assertThat(stats.path("analytics").isObject())
                .as("DOC-23 §11.7; ticketing has no recompute before P6-05, so no detector has an entry")
                .isTrue();
        assertThat(stats.path("analytics").isEmpty()).isTrue();
        assertThat(stats.path("written").asLong()).isEqualTo(3);
    }

    @Test
    void aReplayWithoutRecomputeAnalyticsSkipsTheStep() {
        Instant hour = hour();
        sales(hour, 0, firstOffset(), 2);

        Map<String, Object> done = awaitRequest(rawReplay(hour, hour.plus(1, ChronoUnit.HOURS)));

        assertThat(done).containsEntry("status", "DONE");
        assertThat(steps(done)).containsExactly("listObjects", "replayRecords");
        assertThat(stats(done).path("analytics_recomputed").asBoolean()).isFalse();
        assertThat(stats(done).has("analytics")).isFalse();
    }

    @Test
    void r13AWindowWithoutObjectsIsDone() {
        Instant hour = hour();
        Map<String, Object> done = awaitRequest(rawReplay(hour, hour.plus(2, ChronoUnit.HOURS)));

        assertThat(done).containsEntry("status", "DONE");
        assertThat(stats(done).path("objects").asLong()).isZero();
    }

    @Test
    void r14AndR15BrokenLinesAndObjectsGoToTheDeadLetterQueue() {
        Instant hour = hour();
        long offset = firstOffset();
        String good = UUID.randomUUID().toString();
        object(
                hour,
                0,
                offset,
                List.of(
                        line(offset, hour.plusSeconds(1), sale(good, "5.00")),
                        "not json at all",
                        line(offset + 2, hour.plusSeconds(2), new byte[] {(byte) 0xFF, (byte) 0xC3, 0x00})));
        ((LocalRawZone) raw).write(key(hour, 1, offset), "this is not gzip".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> done = awaitRequest(rawReplay(hour, hour.plus(1, ChronoUnit.HOURS)));

        assertThat(done).as(done::toString).containsEntry("status", "DONE");
        assertThat(facts(List.of(good))).isEqualTo(1);
        List<Map<String, Object>> letters = deadLetters(offset, offset + 2);
        assertThat(letters).extracting(r -> r.get("kafka_offset")).containsExactly(offset + 2);
        assertThat(letters.getFirst()).containsEntry("stage", "DESERIALIZE");
        assertThat(stats(done).path("skipped").asLong()).isEqualTo(3);
    }

    @Test
    void r16OverlappingObjectsAreReadOncePerOffset() {
        Instant hour = hour();
        long base = firstOffset();
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (long o = base + 100; o < base + 400; o++) {
            String id = "00000000-0000-7000-8000-" + String.format("%012d", o % 1_000_000_000_000L);
            if (o < base + 300) {
                first.add(line(o, hour.plusSeconds(5), sale(id, "5.00")));
            }
            if (o >= base + 150) {
                second.add(line(o, hour.plusSeconds(5), sale(id, "5.00")));
            }
            ids.add(id);
        }
        object(hour, 3, base + 100, first);
        object(hour, 3, base + 150, second);

        Map<String, Object> done = awaitRequest(rawReplay(hour, hour.plus(1, ChronoUnit.HOURS)));

        assertThat(done).containsEntry("status", "DONE");
        assertThat(stats(done).path("duplicate").asLong()).isEqualTo(150);
        assertThat(stats(done).path("processed").asLong()).isEqualTo(300);
        assertThat(facts(ids)).isEqualTo(300);
    }

    @Test
    void r17ThePartitionComesFromTheNameAndTheWindowFiltersByRecordTime() {
        Instant hour = hour();
        long offset = firstOffset();
        String inside = UUID.randomUUID().toString();
        String before = UUID.randomUUID().toString();
        object(
                hour,
                7,
                offset,
                List.of(
                        line(offset, hour.plusSeconds(10), sale(before, "5.00")),
                        line(offset + 1, hour.plusSeconds(1000), sale(inside, "900.00"))));

        Map<String, Object> done = awaitRequest(rawReplay(hour.plusSeconds(500), hour.plus(1, ChronoUnit.HOURS)));

        assertThat(done).containsEntry("status", "DONE");
        assertThat(stats(done).path("filtered").asLong()).isEqualTo(1);
        assertThat(facts(List.of(before))).isZero();
        List<Map<String, Object>> letters = jdbc.queryForList(
                "SELECT kafka_partition, stage FROM ops.dead_letter WHERE kafka_topic = ? AND kafka_offset = ?",
                TOPIC,
                offset + 1);
        assertThat(letters).singleElement().satisfies(r -> assertThat(r).containsEntry("kafka_partition", 7));
    }

    @Test
    void r07AReplayThatNowSucceedsResolvesTheOldDeadLetter() {
        Instant hour = hour();
        long offset = firstOffset();
        String id = UUID.randomUUID().toString();
        UUID deadLetter = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ops.dead_letter (id, source, stage, error_class, error_message, raw_payload, kafka_topic,
                                             kafka_partition, kafka_offset, batch_id)
                VALUES (?, 'TICKETING_SALES', 'QUALITY', 'DQ-09', 'old rule', '{}', ?, 0, ?, ?)
                """, deadLetter, TOPIC, offset, UUID.randomUUID());
        object(hour, 0, offset, List.of(line(offset, hour.plusSeconds(3), sale(id, "5.00"))));

        Map<String, Object> done = awaitRequest(rawReplay(hour, hour.plus(1, ChronoUnit.HOURS)));

        assertThat(done).containsEntry("status", "DONE");
        assertThat(stats(done).path("dlq_resolved").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, resolved_by FROM ops.dead_letter WHERE id = ?", deadLetter))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolved_by", "system:etl-batch");
        assertThat(jdbc.queryForList(
                        "SELECT action FROM ops.dlq_action_log WHERE dead_letter_id = ?", String.class, deadLetter))
                .containsExactly("RESOLVED");
    }

    @Test
    void r08AFailedReplayIsRestartedThroughAJobRequestAndFinishes() {
        Instant hour = hour();
        long offset = firstOffset();
        List<String> ids = sales(hour, 0, offset, 1200);
        faults.arm(FaultPoint.BEFORE_WRITE, FaultAction.THROW_FATAL, 1, 1);

        UUID request = rawReplay(hour, hour.plus(1, ChronoUnit.HOURS));
        Map<String, Object> failed = awaitRequest(request);
        assertThat(failed).containsEntry("status", "FAILED");
        assertThat(facts(ids)).as("the first chunk committed").isEqualTo(500);

        api.update("""
                INSERT INTO ops.job_request (id, kind, job_name, target_job_execution_id, requested_by)
                VALUES (?, 'RESTART', 'RawZoneReplayJob', ?, 'user:test')
                """, UUID.randomUUID(), failed.get("job_execution_id"));
        await().atMost(Duration.ofSeconds(60))
                .until(() -> "DONE"
                        .equals(api.queryForObject(
                                "SELECT status FROM ops.replay_request WHERE id = ?", String.class, request)));

        assertThat(facts(ids)).isEqualTo(1200);
        Map<String, Object> done = api.queryForMap(
                "SELECT stats::text AS stats, job_execution_id FROM ops.replay_request WHERE id = ?", request);
        assertThat(done.get("job_execution_id")).isNotEqualTo(failed.get("job_execution_id"));
        assertThat(stats(done).path("lines_read").asLong())
                .as("reader counters survive the restart")
                .isEqualTo(1200);
    }

    private UUID deadLetterFromReplay(String transactionId, long offset) {
        Instant hour = hour();
        object(hour, 0, offset, List.of(line(offset, hour.plusSeconds(3), sale(transactionId, "900.00"))));
        assertThat(awaitRequest(rawReplay(hour, hour.plus(1, ChronoUnit.HOURS))))
                .containsEntry("status", "DONE");
        List<Map<String, Object>> letters = deadLetters(offset, offset);
        assertThat(letters).hasSize(1);
        return (UUID) letters.getFirst().get("id");
    }

    private UUID dlqReplay(UUID deadLetter, String editedPayload) {
        api.update(
                "UPDATE ops.dead_letter SET status = 'REPLAY_REQUESTED', edited_payload = ?::jsonb WHERE id = ?",
                editedPayload,
                deadLetter);
        UUID id = UUID.randomUUID();
        api.update("""
                INSERT INTO ops.replay_request (id, kind, source, dead_letter_id, requested_by)
                VALUES (?, 'DLQ_RECORD', 'TICKETING_SALES', ?, 'user:test')
                """, id, deadLetter);
        return id;
    }

    @Test
    void r01AnEditedDeadLetterIsReplayedIntoTheFacts() {
        String id = UUID.randomUUID().toString();
        long offset = firstOffset();
        UUID deadLetter = deadLetterFromReplay(id, offset);

        Map<String, Object> done =
                awaitRequest(dlqReplay(deadLetter, new String(sale(id, "5.00"), StandardCharsets.UTF_8)));

        assertThat(done).as(done::toString).containsEntry("status", "DONE");
        assertThat(stats(done).path("outcome").asString()).isEqualTo("REPLAYED");
        assertThat(facts(List.of(id))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ops.dead_letter WHERE id = ?", String.class, deadLetter))
                .isEqualTo("REPLAYED");
        assertThat(jdbc.queryForList(
                        "SELECT action FROM ops.dlq_action_log WHERE dead_letter_id = ? ORDER BY id",
                        String.class,
                        deadLetter))
                .endsWith("REPLAYED");
    }

    @Test
    void r02AReplayThatFailsAgainPutsTheSameRowBackToNew() {
        String id = UUID.randomUUID().toString();
        long offset = firstOffset();
        UUID deadLetter = deadLetterFromReplay(id, offset);
        int before = ((Number) jdbc.queryForObject(
                        "SELECT replay_count FROM ops.dead_letter WHERE id = ?", Integer.class, deadLetter))
                .intValue();

        Map<String, Object> done =
                awaitRequest(dlqReplay(deadLetter, new String(sale(id, "900.00"), StandardCharsets.UTF_8)));

        assertThat(done).containsEntry("status", "DONE");
        assertThat(stats(done).path("outcome").asString()).isEqualTo("FAILED_AGAIN");
        assertThat(deadLetters(offset, offset))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("status", "NEW").containsEntry("replay_count", before + 1));
        assertThat(facts(List.of(id))).isZero();
    }

    @Test
    void aDeadLetterThatIsNotWaitingFailsTheRequest() {
        String id = UUID.randomUUID().toString();
        UUID deadLetter = deadLetterFromReplay(id, firstOffset());
        UUID request = UUID.randomUUID();
        api.update("""
                INSERT INTO ops.replay_request (id, kind, source, dead_letter_id, requested_by)
                VALUES (?, 'DLQ_RECORD', 'TICKETING_SALES', ?, 'user:test')
                """, request, deadLetter);

        Map<String, Object> failed = awaitRequest(request);

        assertThat(failed).containsEntry("status", "FAILED");
        assertThat((String) failed.get("message")).contains("expected REPLAY_REQUESTED");
    }
}
