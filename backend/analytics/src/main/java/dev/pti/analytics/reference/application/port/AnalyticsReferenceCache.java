package dev.pti.analytics.reference.application.port;

import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.DayType;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The schedule data analytics needs beyond {@code ReferenceData} (DOC-23 §3): routes, trip patterns, scheduled
 * headways and the day type of a date. All of it belongs to one feed version at a time; when the ACTIVE feed changes
 * the implementation drops what it holds and loads again (DOC-21 §6).
 *
 * <p>While no feed is ACTIVE, {@link #hasActiveFeed()} is false and every other method throws
 * {@code IllegalStateException}: a detector checks first and answers {@code NOOP} (DOC-23 §15).
 */
public interface AnalyticsReferenceCache {

    /** False until a feed version is ACTIVE. */
    boolean hasActiveFeed();

    long feedVersionId();

    /** {@code agency_timezone} of the feed, {@code America/Chicago}: the zone of every local date and hour. */
    ZoneId agencyZone();

    Optional<RouteInfo> route(String routeId);

    /** Empty for a trip that the ACTIVE feed does not have. */
    Optional<TripPattern> trip(String tripId);

    /** Empty when the feed has no headway for the hour ({@code NULL} means fewer than two trips that hour). */
    OptionalInt scheduledHeadway(String routeId, int directionId, DayType dayType, int hourOfServiceDay);

    /** From {@code dw.dim_date}; dates outside its range use the rules of {@link DayType#of}. */
    DayType dayType(LocalDate serviceDate);
}
