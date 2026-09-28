package dev.pti.simulator.ticketing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The documented ticketing defaults (DOC-29 §3.2, DOC-25 §9.2). */
public final class TicketingDefaults {

    public static final List<Double> RATE_PROFILE = List.of(
            0.05, 0.05, 0.05, 0.05, 0.05, 0.3, 1.2, 2.0, 1.6, 0.9, 0.8, 0.8, 0.8, 0.8, 0.9, 1.4, 2.0, 1.8, 1.1, 0.7,
            0.5, 0.35, 0.25, 0.1);

    private TicketingDefaults() {}

    public static TicketingSettings settings() {
        return new TicketingSettings(
                new TicketingSettings.Fare(
                        new BigDecimal("2.00"),
                        new BigDecimal("2.50"),
                        new BigDecimal("5.00"),
                        new BigDecimal("76.00")),
                Map.of(TicketType.SINGLE, 0.80, TicketType.DAY, 0.15, TicketType.MONTH, 0.05),
                0.01,
                0.002,
                0.0005,
                60,
                RATE_PROFILE,
                0.6,
                50_000);
    }
}
