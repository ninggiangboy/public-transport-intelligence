package dev.pti.simulator.ticketing;

import org.jspecify.annotations.Nullable;

/**
 * A row of {@code ticketing_source.public.sale_point} (DOC-13 §5.4, DOC-25 §9.1).
 *
 * @param weight how often the point sells, relative to the other points of its kind
 */
public record SalePoint(
        String id,
        String name,
        Kind kind,
        @Nullable String stopId,
        @Nullable String routeId,
        double weight) {

    public enum Kind {
        KIOSK,
        ONBOARD,
        APP
    }
}
