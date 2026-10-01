package dev.pti.api.insight.application.port;

import dev.pti.api.insight.application.DisruptionQuery;
import dev.pti.api.insight.domain.DisruptionEpisode;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.util.Optional;
import java.util.UUID;

/** Reads disruption episodes with the audience and severity of their alert (DOC-32 E-12, E-13). */
public interface DisruptionReader {

    /** Newest {@code episode_start} first, then {@code id} descending. */
    Page<DisruptionEpisode> list(DisruptionQuery query, PageRequest request);

    Optional<DisruptionEpisode> find(UUID id);
}
