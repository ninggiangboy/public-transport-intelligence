package dev.pti.api.transit.application.port;

import dev.pti.api.transit.domain.StopDisruption;
import dev.pti.common.events.Audience;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** The open disruption alerts of some routes that a caller may see (DOC-32 E-07). Never cached. */
public interface StopDisruptionReader {

    /** At most 20, the most severe first, then the newest. */
    List<StopDisruption> findOpen(Collection<String> routeIds, Set<Audience> audiences);
}
