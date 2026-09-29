package dev.pti.etl.core;

import java.util.Arrays;
import java.util.Optional;

/** The value domain {@code ops.etl_source} (DOC-15 §3.2) and the topic each streaming source reads (DOC-09 §1). */
public enum EtlSource {
    GTFS_RT_VEHICLE_POSITION("gtfs.vehicle_positions"),
    GTFS_RT_TRIP_UPDATE("gtfs.trip_updates"),
    TICKETING_SALES("ticketing.sales.cdc"),
    TICKETING_SALE_POINTS("ticketing.sale_points.cdc"),
    GTFS_STATIC(null);

    private final String topic;

    EtlSource(String topic) {
        this.topic = topic;
    }

    /** The topic name without the test prefix; empty for {@link #GTFS_STATIC}. */
    public Optional<String> topic() {
        return Optional.ofNullable(topic);
    }

    public String requireTopic() {
        return topic().orElseThrow(() -> new IllegalStateException(this + " has no topic"));
    }

    public static Optional<EtlSource> ofTopic(String topic) {
        return Arrays.stream(values())
                .filter(s -> s.topic != null && s.topic.equals(topic))
                .findFirst();
    }
}
