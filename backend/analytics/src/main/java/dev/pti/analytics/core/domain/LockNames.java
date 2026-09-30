package dev.pti.analytics.core.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** The advisory lock names of DOC-23 §2.5, one per unit of work. */
public final class LockNames {

    private static final String PREFIX = "pti:analytics:";

    private LockNames() {}

    public static String bunching(String routeId) {
        return PREFIX + "bunching:" + routeId;
    }

    /** Both directions of the route share one lock. */
    public static String disruption(String routeId) {
        return PREFIX + "disruption:" + routeId;
    }

    /** The whole ETA table. */
    public static String eta() {
        return PREFIX + "eta";
    }

    public static String otp(LocalDate serviceDate) {
        return PREFIX + "otp:" + DateTimeFormatter.ISO_LOCAL_DATE.format(serviceDate);
    }

    public static String ticketing(Instant windowStart) {
        return PREFIX + "ticketing:" + DateTimeFormatter.ISO_INSTANT.format(windowStart);
    }
}
