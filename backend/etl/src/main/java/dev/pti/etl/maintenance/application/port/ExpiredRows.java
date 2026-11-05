package dev.pti.etl.maintenance.application.port;

import dev.pti.etl.maintenance.application.PurgeTarget;
import java.time.OffsetDateTime;

/** Deletes expired rows from one table, in batches, in the caller's transaction (DOC-18 §5). */
public interface ExpiredRows {

    /** Deletes at most {@code limit} expired rows of one target; returns how many it deleted. */
    int deleteBatch(PurgeTarget target, OffsetDateTime cutoff, int limit);
}
