package dev.pti.etl.write;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.common.error.ErrorKind;
import dev.pti.common.json.MessageJson;
import dev.pti.common.pii.PiiScrubber;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.Utf8;
import dev.pti.etl.metrics.ErrorMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link DeadLetterWriter} on {@code ops.dead_letter} (DOC-22 §1). The payload is PII-scrubbed (DR-60), stored as
 * text with U+0000 replaced and cut at {@code pti.dlq.max-payload-bytes}; the message is cut at 4,000 characters.
 */
public final class JdbcDeadLetterWriter implements DeadLetterWriter {

    static final int MAX_ERROR_MESSAGE = 4000;
    static final String REPLAY_ACTOR = "system:etl-batch";

    private static final String INSERT_LIVE = SqlResource.load("insert_dead_letter_live");
    private static final String UPSERT_REPLAY = SqlResource.load("upsert_dead_letter_replay");
    private static final String INSERT_ACTION = """
            INSERT INTO ops.dlq_action_log (dead_letter_id, action, actor, details)
            VALUES (:id, 'REPLAY_FAILED', :actor, CAST(:details AS jsonb))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PiiScrubber scrubber;
    private final int maxPayloadBytes;
    private final MeterRegistry meters;

    public JdbcDeadLetterWriter(
            NamedParameterJdbcTemplate jdbc, PiiScrubber scrubber, int maxPayloadBytes, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.scrubber = scrubber;
        this.maxPayloadBytes = maxPayloadBytes;
        this.meters = meters;
    }

    @Override
    public DeadLetterResult write(DeadLetter letter) {
        int rows = jdbc.update(INSERT_LIVE, params(letter));
        DeadLetterResult result = rows == 1 ? DeadLetterResult.INSERTED : DeadLetterResult.IGNORED;
        count(letter, "live", result);
        return result;
    }

    @Override
    public DeadLetterResult writeReplay(DeadLetter letter) {
        List<Map<String, Object>> rows = jdbc.queryForList(UPSERT_REPLAY, params(letter));
        Map<String, Object> row = rows.getFirst();
        boolean inserted = Boolean.TRUE.equals(row.get("inserted"));
        if (!inserted) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("stage", row.get("old_stage"));
            details.put("error_class", row.get("old_error_class"));
            details.put("batch_id", letter.batchId().toString());
            jdbc.update(
                    INSERT_ACTION,
                    new MapSqlParameterSource()
                            .addValue("id", row.get("id"))
                            .addValue("actor", REPLAY_ACTOR)
                            .addValue("details", MessageJson.mapper().writeValueAsString(details)));
        }
        DeadLetterResult result = inserted ? DeadLetterResult.INSERTED : DeadLetterResult.UPDATED;
        count(letter, "replay", result);
        return result;
    }

    private MapSqlParameterSource params(DeadLetter letter) {
        InboundMessage m = letter.message();
        Instant recorded = m.recordTimestamp();
        String businessKey = letter.businessKey();
        return new MapSqlParameterSource()
                .addValue("id", UuidCreator.getTimeOrderedEpoch())
                .addValue("source", m.source().name())
                .addValue("stage", letter.stage().name())
                .addValue("error_class", clean(letter.errorClass()))
                .addValue("error_message", errorMessage(letter.errorMessage()))
                .addValue("rule_id", letter.ruleId(), Types.VARCHAR)
                .addValue("raw_payload", payload(m.value()))
                .addValue("kafka_topic", m.topic(), Types.VARCHAR)
                .addValue("kafka_partition", m.partition(), Types.INTEGER)
                .addValue("kafka_offset", m.offset(), Types.BIGINT)
                .addValue("kafka_timestamp", recorded == null ? null : Timestamp.from(recorded), Types.TIMESTAMP)
                .addValue("business_key", businessKey == null ? null : clean(businessKey), Types.VARCHAR)
                .addValue("batch_id", letter.batchId());
    }

    /** Scrubbed, NUL-free text of at most {@code maxPayloadBytes} UTF-8 bytes (DOC-22 §1.2). */
    String payload(byte @Nullable [] value) {
        if (value == null) {
            return "";
        }
        String text = scrubber.scrub(Utf8.forStorage(value));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxPayloadBytes) {
            return text;
        }
        // Room for the longest suffix: the number removed never has more digits than the whole length.
        int cut = maxPayloadBytes - suffix(bytes.length).getBytes(StandardCharsets.UTF_8).length;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--; // never split a multi-byte character
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8) + suffix(bytes.length - cut);
    }

    private static String suffix(int removed) {
        return "…[truncated " + removed + " bytes]";
    }

    static String errorMessage(String message) {
        String text = clean(message);
        if (text.length() <= MAX_ERROR_MESSAGE) {
            return text;
        }
        int end = text.offsetByCodePoints(0, MAX_ERROR_MESSAGE - 1);
        return text.substring(0, end) + "…";
    }

    private static String clean(String text) {
        return text.replace('\u0000', '�');
    }

    private void count(DeadLetter letter, String mode, DeadLetterResult result) {
        String source = letter.message().source().name();
        String stage = letter.stage().name();
        String rule = letter.ruleId() == null ? "none" : letter.ruleId();
        String resultTag = result.name().toLowerCase(Locale.ROOT);
        Counter records = Counter.builder("pti.dlq.records")
                .tag("source", source)
                .tag("stage", stage)
                .tag("mode", mode)
                .tag("result", resultTag)
                .register(meters);
        Counter violations = Counter.builder("pti.dq.violations")
                .tag("source", source)
                .tag("stage", stage)
                .tag("rule", rule)
                .register(meters);
        Counter errors = ErrorMetrics.counter(meters, ErrorKind.DATA, letter.errorClass());
        AfterCommit.run(() -> {
            records.increment();
            if (result != DeadLetterResult.IGNORED) {
                violations.increment();
                errors.increment();
            }
        });
    }
}
