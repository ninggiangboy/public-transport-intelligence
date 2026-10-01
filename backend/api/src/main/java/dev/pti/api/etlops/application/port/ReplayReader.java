package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.ReplayDetail;
import dev.pti.api.etlops.domain.ReplayEstimate;
import dev.pti.api.etlops.domain.ReplayFilter;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reads the replays and the micro-batch log an estimate rests on (DOC-32 E-51…E-53), as {@code api_reader}. */
public interface ReplayReader {

    /** Newest first by {@code (requested_at, id)} descending. */
    Page<ReplayRequest> list(ReplayFilter filter, PageRequest page);

    /** The replay, with the progress of its running step when it is {@code RUNNING}. */
    Optional<ReplayDetail> find(UUID id);

    /**
     * The micro-batch log for the estimate: the records read by the batches of the source that started in {@code
     * [from, to + LOOKAHEAD)} (a record is processed a few seconds after the raw zone got it), and how many minutes of
     * {@code [from, to)} have a batch.
     */
    ReplayEstimate.History history(String source, Instant from, Instant to);

    /** Whether a raw zone replay of the source is waiting or running. */
    boolean rawReplayActive(String source);
}
