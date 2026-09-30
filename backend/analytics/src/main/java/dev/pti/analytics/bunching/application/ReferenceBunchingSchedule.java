package dev.pti.analytics.bunching.application;

import dev.pti.analytics.bunching.domain.BunchingSchedule;
import dev.pti.analytics.core.domain.ServiceDates;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.TripPattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The {@link BunchingSchedule} of the ACTIVE feed: trips and headways from the reference cache. The headway is looked
 * up by the day type of the service date and the hour of that service day (DOC-23 §2.1).
 */
final class ReferenceBunchingSchedule implements BunchingSchedule {

    private final AnalyticsReferenceCache reference;

    ReferenceBunchingSchedule(AnalyticsReferenceCache reference) {
        this.reference = reference;
    }

    @Override
    public Optional<TripPattern> trip(String tripId) {
        return reference.trip(tripId);
    }

    @Override
    public OptionalInt scheduledHeadway(String routeId, int directionId, LocalDate serviceDate, Instant at) {
        int hour = ServiceDates.hourOfServiceDay(serviceDate, at, reference.agencyZone());
        return reference.scheduledHeadway(routeId, directionId, reference.dayType(serviceDate), hour);
    }
}
