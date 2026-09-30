package dev.pti.analytics.alert.domain;

import org.jspecify.annotations.Nullable;

/**
 * The English alert titles of DOC-23 §10.1. A title has at most {@value #MAX_LENGTH} characters ({@code
 * alert_event.title} has a CHECK); a longer one is cut and ends with an ellipsis.
 */
public final class AlertTitles {

    public static final int MAX_LENGTH = 200;

    private static final String ELLIPSIS = "…";

    private AlertTitles() {}

    /**
     * {@code Bus bunching on route {route} {direction}: vehicles {leader} and {follower}}
     *
     * @param routeLabel {@code RouteInfo.label}
     * @param directionLabel the label of the direction, e.g. {@code Northbound}
     */
    public static String bunching(String routeLabel, String directionLabel, String leader, String follower) {
        return limit("Bus bunching on route " + routeLabel + " " + directionLabel + ": vehicles " + leader + " and "
                + follower);
    }

    /** {@code Delays on route {route} {direction}} */
    public static String disruption(String routeLabel, String directionLabel) {
        return limit("Delays on route " + routeLabel + " " + directionLabel);
    }

    /**
     * The title of a ticketing anomaly.
     *
     * @param salePointName {@code dim_sale_point.name}; when {@code null} or blank the id is used
     */
    public static String ticketingAnomaly(AnomalyTrigger trigger, @Nullable String salePointName, String salePointId) {
        String salePoint = salePointName == null || salePointName.isBlank() ? salePointId : salePointName;
        return limit(
                switch (trigger) {
                    case VOLUME -> "Unusual ticket sales at " + salePoint;
                    case REFUND_RATIO -> "High refund rate at " + salePoint;
                    case BOTH -> "Unusual sales and refund rate at " + salePoint;
                });
    }

    /** Cuts {@code title} to {@value #MAX_LENGTH} characters, counted in code points like PostgreSQL does. */
    static String limit(String title) {
        if (title.codePointCount(0, title.length()) <= MAX_LENGTH) {
            return title;
        }
        return title.substring(0, title.offsetByCodePoints(0, MAX_LENGTH - 1)) + ELLIPSIS;
    }
}
