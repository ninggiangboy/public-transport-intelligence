package dev.pti.etl.stream;

import dev.pti.common.json.MessageJson;
import dev.pti.etl.write.WriteMode;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code ops.etl_stream_batch} (DR-22, DR-63): one row per committed poll, written inside its transaction, and a
 * best-effort FAILED row for a poll that rolled back.
 */
public class StreamBatchLog {

    private static final Logger log = LoggerFactory.getLogger(StreamBatchLog.class);
    private static final int MAX_ERROR_MESSAGE = 4000;

    private static final String INSERT = """
            INSERT INTO ops.etl_stream_batch (
              batch_id, source, listener_id, consumer_group, instance_id, offsets, status, write_mode,
              records_read, records_written, records_skipped, records_duplicate,
              min_event_ts, max_event_ts, min_record_ts, started_at, finished_at, error_class, error_message)
            VALUES (
              :batch_id, :source, :listener_id, :consumer_group, :instance_id, CAST(:offsets AS jsonb), :status,
              :write_mode, :read, :written, :skipped, :duplicate,
              :min_event_ts, :max_event_ts, :min_record_ts, :started_at, :finished_at, :error_class, :error_message)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public StreamBatchLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** In the chunk transaction. */
    public void insert(StreamChunkRequest request, StreamChunkResult result, Instant startedAt, Instant finishedAt) {
        jdbc.update(
                INSERT,
                common(request, startedAt, finishedAt)
                        .addValue("status", result.status().name())
                        .addValue("write_mode", result.writeMode().name())
                        .addValue("read", result.read())
                        .addValue("written", result.written())
                        .addValue("skipped", result.skipped())
                        .addValue("duplicate", result.duplicate())
                        .addValue("min_event_ts", ts(result.minEventTs()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("max_event_ts", ts(result.maxEventTs()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("error_class", null, Types.VARCHAR)
                        .addValue("error_message", null, Types.VARCHAR));
    }

    /** Outside any transaction, after a rollback. Never throws: the database may be the reason for the failure. */
    public void insertFailedBestEffort(
            StreamChunkRequest request, WriteMode mode, Throwable error, Instant startedAt, Instant finishedAt) {
        String message = String.valueOf(error.getMessage()).replace('\u0000', '�');
        try {
            jdbc.update(
                    INSERT,
                    common(request, startedAt, finishedAt)
                            .addValue("status", StreamChunkResult.BatchStatus.FAILED.name())
                            .addValue("write_mode", mode.name())
                            .addValue("read", request.messages().size())
                            .addValue("written", 0)
                            .addValue("skipped", 0)
                            .addValue("duplicate", 0)
                            .addValue("min_event_ts", null, Types.TIMESTAMP_WITH_TIMEZONE)
                            .addValue("max_event_ts", null, Types.TIMESTAMP_WITH_TIMEZONE)
                            .addValue("error_class", error.getClass().getSimpleName())
                            .addValue(
                                    "error_message",
                                    message.length() > MAX_ERROR_MESSAGE
                                            ? message.substring(0, MAX_ERROR_MESSAGE)
                                            : message));
        } catch (RuntimeException e) {
            log.debug("Could not record the failed batch {}: {}", request.batchId(), e.toString());
        }
    }

    private static MapSqlParameterSource common(StreamChunkRequest request, Instant startedAt, Instant finishedAt) {
        return new MapSqlParameterSource()
                .addValue("batch_id", request.batchId())
                .addValue("source", request.source().name())
                .addValue("listener_id", request.listenerId())
                .addValue("consumer_group", request.consumerGroup())
                .addValue("instance_id", request.instanceId())
                .addValue("offsets", MessageJson.mapper().writeValueAsString(request.offsets()))
                .addValue("min_record_ts", ts(request.minRecordTimestamp().orElse(null)), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("started_at", ts(startedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("finished_at", ts(finishedAt), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
