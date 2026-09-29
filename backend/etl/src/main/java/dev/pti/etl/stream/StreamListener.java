package dev.pti.etl.stream;

import dev.pti.etl.core.EtlSource;
import java.util.Arrays;
import java.util.List;

/** The four listeners of {@code etl-stream} (DOC-20 §1). */
public enum StreamListener {
    GTFS_RT_VEHICLE_POSITION(
            "gtfs-rt-vehicle-position",
            EtlSource.GTFS_RT_VEHICLE_POSITION,
            "pti-etl-gtfs-rt",
            StreamListener.GTFS_RT_FLAG),
    GTFS_RT_TRIP_UPDATE(
            "gtfs-rt-trip-update", EtlSource.GTFS_RT_TRIP_UPDATE, "pti-etl-gtfs-rt", StreamListener.GTFS_RT_FLAG),
    TICKETING_SALES("ticketing-sales", EtlSource.TICKETING_SALES, "pti-etl-ticketing", StreamListener.TICKETING_FLAG),
    TICKETING_SALE_POINTS(
            "ticketing-sale-points",
            EtlSource.TICKETING_SALE_POINTS,
            "pti-etl-ticketing",
            StreamListener.TICKETING_FLAG);

    /** Runtime flags that pause a group of listeners (DOC-20 §7, DR-19). */
    public static final String GTFS_RT_FLAG = "etl.consumer.gtfs-rt.paused";

    public static final String TICKETING_FLAG = "etl.consumer.ticketing.paused";

    private final String id;
    private final EtlSource source;
    private final String groupId;
    private final String pauseFlag;

    StreamListener(String id, EtlSource source, String groupId, String pauseFlag) {
        this.id = id;
        this.source = source;
        this.groupId = groupId;
        this.pauseFlag = pauseFlag;
    }

    public String id() {
        return id;
    }

    public EtlSource source() {
        return source;
    }

    public String groupId() {
        return groupId;
    }

    public String pauseFlag() {
        return pauseFlag;
    }

    /** GTFS-realtime listeners wait for an ACTIVE feed (DOC-20 §6). */
    public boolean needsReferenceData() {
        return pauseFlag.equals(GTFS_RT_FLAG);
    }

    public static List<StreamListener> pausedBy(String flag) {
        return Arrays.stream(values()).filter(l -> l.pauseFlag.equals(flag)).toList();
    }
}
