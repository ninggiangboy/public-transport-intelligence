package dev.pti.simulator.control;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The body of {@code GET /sim/status} (DOC-25 §8). */
public record SimStatus(
        Clock clock,
        Feed feed,
        Rate rate,
        int activeVehicles,
        int activeTrips,
        Map<String, Double> messagesPerSecond,
        double ticketingPerSecond,
        Ledger ledger,
        List<RunningScenario> runningScenarios) {

    /**
     * @param businessNow business time
     * @param offset ISO-8601 duration added to real time
     */
    public record Clock(Instant businessNow, String offset, String agencyTimeZone, List<ServiceDate> serviceDates) {}

    public record ServiceDate(LocalDate realDate, @Nullable LocalDate feedDate) {}

    public record Feed(String sha256, LocalDate validFrom, LocalDate validTo) {}

    public record Rate(double gtfsRt, double ticketing) {}

    /** @param lastFlushAt real time */
    public record Ledger(int queueDepth, @Nullable Instant lastFlushAt) {}

    /** Filled by the scenario engine (P3). */
    public record RunningScenario(String runId, String scenario, Instant plannedEndAt) {}
}
