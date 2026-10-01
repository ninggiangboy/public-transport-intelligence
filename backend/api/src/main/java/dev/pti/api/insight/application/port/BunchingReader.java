package dev.pti.api.insight.application.port;

import dev.pti.api.insight.application.BunchingQuery;
import dev.pti.api.insight.domain.BunchingDetail;
import dev.pti.api.insight.domain.BunchingEpisode;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.util.Optional;
import java.util.UUID;

/** Reads bunching episodes with their dispatch suggestion (DOC-32 E-10, E-11). */
public interface BunchingReader {

    /** Newest {@code episode_start} first, then {@code id} descending. */
    Page<BunchingEpisode> list(BunchingQuery query, PageRequest request);

    Optional<BunchingDetail> find(UUID id);
}
