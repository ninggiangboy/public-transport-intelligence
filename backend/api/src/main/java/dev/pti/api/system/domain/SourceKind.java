package dev.pti.api.system.domain;

import dev.pti.api.platform.domain.AsOfKind;

/** The three sources whose freshness the API reports (DOC-32 E-60), named as in {@code ops.etl_source}. */
public enum SourceKind {
    GTFS_RT_VEHICLE_POSITION(true, AsOfKind.VEHICLE_POSITION),
    GTFS_RT_TRIP_UPDATE(true, AsOfKind.TRIP_UPDATE),
    TICKETING_SALES(false, AsOfKind.TICKET_SALES);

    private final boolean gtfsRealtime;
    private final AsOfKind asOfKind;

    SourceKind(boolean gtfsRealtime, AsOfKind asOfKind) {
        this.gtfsRealtime = gtfsRealtime;
        this.asOfKind = asOfKind;
    }

    /** True for the two feeds whose staleness raises the banner and the {@code GtfsRtFeedStale} alert. */
    public boolean gtfsRealtime() {
        return gtfsRealtime;
    }

    /** The {@code X-Data-As-Of} kind that this source's last event time answers. */
    public AsOfKind asOfKind() {
        return asOfKind;
    }
}
