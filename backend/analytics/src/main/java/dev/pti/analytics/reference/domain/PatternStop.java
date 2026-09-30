package dev.pti.analytics.reference.domain;

/**
 * One stop of a trip pattern (DOC-23 §3). Times are GTFS seconds of the service day; an absolute instant comes from
 * {@code GtfsTime.toInstant} with the {@code service_date} of the fact row.
 *
 * @param dist progress along the trip: {@code shape_dist_traveled}, or cumulative meters between the stops. It is only
 *     ever used to interpolate in proportion, so the unit does not matter
 */
public record PatternStop(
        int stopSequence,
        String stopId,
        int arrivalSeconds,
        int departureSeconds,
        double dist,
        double lat,
        double lon) {}
