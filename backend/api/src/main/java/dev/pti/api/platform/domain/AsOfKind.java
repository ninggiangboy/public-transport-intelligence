package dev.pti.api.platform.domain;

/**
 * The data a response can be as fresh as, for {@code X-Data-As-Of} (DOC-31 §7.3). A use case names the kind its answer
 * rests on; the value comes from the freshness probe, never from an extra query. The static GTFS feed is not here: its
 * as-of is {@link ActiveFeed#activatedAt()}, and {@code /alerts} reports the newest {@code created_at} of its own rows.
 */
public enum AsOfKind {
    /** {@code lastEventAt} of {@code GTFS_RT_VEHICLE_POSITION}. */
    VEHICLE_POSITION,
    /** {@code lastEventAt} of {@code GTFS_RT_TRIP_UPDATE}. */
    TRIP_UPDATE,
    /** {@code lastEventAt} of {@code TICKETING_SALES}. */
    TICKET_SALES,
    /** {@code max(computed_at)} of {@code insight_eta_prediction}. */
    ETA_PREDICTION,
    /** {@code max(computed_at)} of {@code insight_otp_scorecard}. */
    OTP_SCORECARD
}
