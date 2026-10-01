package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.ActionLogFilter;
import dev.pti.api.etlops.domain.ActionLogItem;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterFilter;
import dev.pti.api.etlops.domain.DeadLetterItem;
import dev.pti.api.etlops.domain.DeadLetterSummary;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reads the dead letter queue and its action log (DOC-32 E-40…E-42, E-48), as {@code api_reader}. */
public interface DeadLetterReader {

    /** Newest first by {@code (created_at, id)} descending. */
    Page<DeadLetterItem> list(DeadLetterFilter filter, PageRequest page);

    /** @param now the real time now; {@code createdLastHour} counts the dead letters of the hour before it */
    DeadLetterSummary summary(Instant now);

    /** The dead letter with its last 100 actions and its {@code DLQ_RECORD} replays; {@code allowedActions} empty. */
    Optional<DeadLetterDetail> find(UUID id);

    /** Newest first by {@code (at, id)} descending. */
    Page<ActionLogItem> actions(ActionLogFilter filter, PageRequest page);
}
