package dev.pti.api.system.application.port;

import dev.pti.api.system.domain.FreshnessSnapshot;

/** The gauge {@code pti_source_last_event_age_seconds} (DOC-28 §3.5, DR-71). */
public interface FreshnessMetrics {

    /** A probe succeeded: report the age of each source that has data. */
    void publish(FreshnessSnapshot snapshot);

    /** The database cannot be read: the gauge is absent, so that {@code TargetDown} or the API alerts speak. */
    void clear();
}
