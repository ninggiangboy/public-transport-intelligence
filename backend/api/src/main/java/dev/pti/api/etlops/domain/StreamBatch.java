package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One micro-batch of a stream run (DOC-32 E-32), from {@code ops.etl_stream_batch}. */
public record StreamBatch(
        String batchId,
        String status,
        String writeMode,
        String instanceId,
        Map<String, Object> offsets,
        int recordsRead,
        int recordsWritten,
        int recordsSkipped,
        int recordsDuplicate,
        @Nullable Instant minEventTs,
        @Nullable Instant maxEventTs,
        Instant startedAt,
        Instant finishedAt,
        @Nullable String errorClass,
        @Nullable String errorMessage) {}
