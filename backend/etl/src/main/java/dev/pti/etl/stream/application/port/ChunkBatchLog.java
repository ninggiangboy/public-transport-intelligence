package dev.pti.etl.stream.application.port;

import dev.pti.etl.stream.application.StreamChunkRequest;
import dev.pti.etl.stream.application.StreamChunkResult;
import dev.pti.etl.write.domain.WriteMode;
import java.time.Instant;

/**
 * The {@code ops.etl_stream_batch} row of one chunk (DR-22, DR-63): written inside the chunk transaction, with a
 * best-effort FAILED row when the transaction rolled back. Implemented by {@code stream.adapter.out.jdbc}.
 */
public interface ChunkBatchLog {

    /** In the chunk transaction. */
    void insert(StreamChunkRequest request, StreamChunkResult result, Instant startedAt, Instant finishedAt);

    /** Outside any transaction, after a rollback. Never throws: the database may be the reason for the failure. */
    void insertFailedBestEffort(
            StreamChunkRequest request, WriteMode mode, Throwable error, Instant startedAt, Instant finishedAt);
}
