package dev.pti.etl.write;

import java.time.Instant;
import java.util.UUID;

/**
 * What one chunk write needs besides its items.
 *
 * @param batchId the {@code batch_id} stamped on every row (DR-63)
 * @param replay true for raw-zone and dead-letter replays: no dedup registry, no DQ-12, {@code :replay = true}
 * @param businessNow the business clock at the start of the chunk (DR-67)
 */
public record WriteContext(UUID batchId, RunMode mode, boolean replay, Instant businessNow) {}
