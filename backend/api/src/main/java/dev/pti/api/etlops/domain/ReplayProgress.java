package dev.pti.api.etlops.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** What the running step of a replay has done so far (DOC-32 E-52), from {@code ops.ops_job_step_v}. */
public record ReplayProgress(
        String step,
        long readCount,
        long writeCount,
        long skipCount,
        @Nullable Instant updatedAt) {}
