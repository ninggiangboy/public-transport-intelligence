package dev.pti.analytics.bunching.application.port;

import dev.pti.analytics.bunching.domain.BunchingEpisode;
import java.util.UUID;

/**
 * Writes episodes to {@code insight.insight_bus_bunching} (DOC-23 §2.4, §5.7). An episode is upserted by its id and
 * only the columns analytics computes are written, so the enrichment columns that triage fills in are never touched.
 */
public interface BunchingEpisodeWriter {

    /**
     * Inserts the episode or updates the row with the same id.
     *
     * @param batchId the {@code batch_id} of the run, written to the row
     * @return what the row was before: the caller emits the events of an opening or a closing only for a change
     */
    Previous upsert(BunchingEpisode episode, UUID batchId);

    /** The state of the row before the upsert. */
    enum Previous {
        /** There was no row: the episode is new. */
        ABSENT,
        /** The row was open. */
        OPEN,
        /** The row was closed. */
        CLOSED
    }
}
