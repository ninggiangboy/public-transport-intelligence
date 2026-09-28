package dev.pti.simulator;

import dev.pti.simulator.motion.DelayParameters;
import dev.pti.simulator.rate.RateControl;
import dev.pti.simulator.ticketing.TicketingSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/** {@code pti.sim.*} (DOC-29 §3.2, DOC-25 §11). Defaults are in {@code application.yml}. */
@ConfigurationProperties("pti.sim")
@Validated
public record SimProperties(
        long seed,

        @NotNull @Pattern(regexp = "auto|fixed:\\d{4}-\\d{2}-\\d{2}")
        String serviceDateMapping,

        @NotNull @Valid Feed feed,
        @NotNull Duration tick,
        @DecimalMin("0") double gpsNoise,
        @NotNull @Valid VehiclePosition vehiclePosition,
        @NotNull @Valid TripUpdate tripUpdate,
        @NotNull @Valid Vehicle vehicle,
        @NotNull @Valid Delay delay,
        @NotNull @Valid RouteFactor routeFactor,
        @NotNull @Valid RateMultiplier rateMultiplier,
        @NotNull @Valid LedgerSettings ledger,
        @NotNull @Valid TicketingSettings ticketing) {

    /** @param sha256 empty to skip the check */
    public record Feed(@NotNull Resource location, @Nullable String sha256) {}

    public record VehiclePosition(
            @NotNull Duration interval,
            @DecimalMin("0") @DecimalMax("1") double v2Ratio) {}

    public record TripUpdate(
            @NotNull Duration interval, @Min(1) @Max(50) int lookaheadStops) {}

    public record Vehicle(
            @NotNull Duration minLayover, @NotNull Duration maxLayoverEmit) {}

    public record PerPeriod(@NotNull Duration peak, @NotNull Duration offPeak) {

        DelayParameters.PerPeriod seconds() {
            return new DelayParameters.PerPeriod(peak.toMillis() / 1000.0, offPeak.toMillis() / 1000.0);
        }
    }

    public record Delay(
            @NotNull @Valid PerPeriod initialMean,
            @NotNull @Valid PerPeriod initialSd,
            @NotNull @Valid PerPeriod drift,
            @NotNull @Valid PerPeriod segmentSd,
            @NotNull @Valid PerPeriod dwellMean,
            @NotNull Duration earlyLimit,
            @NotNull Duration lateLimit,
            @DecimalMin("0.1") @DecimalMax("1") double minSpeedRatio) {}

    public record RouteFactor(
            @DecimalMin("0") @DecimalMax("0.99") double phi,
            @DecimalMin("0") double sigma,
            @NotNull Duration bucket) {}

    public record RateMultiplier(double gtfsRt, double ticketing) {

        @AssertTrue(message = "must be 0 or between 0.1 and 20")
        public boolean isAllowed() {
            return RateControl.allowed(gtfsRt) && RateControl.allowed(ticketing);
        }
    }

    /** {@code pti.sim.ledger.*} (DOC-25 §6.4, DR-28). */
    public record LedgerSettings(
            @Min(1) int queueCapacity,
            @Min(1) int batchSize,
            @NotNull Duration flushInterval,
            @NotNull Duration retention) {}

    public DelayParameters delayParameters() {
        return new DelayParameters(
                delay.initialMean().seconds(),
                delay.initialSd().seconds(),
                delay.drift().seconds(),
                delay.segmentSd().seconds(),
                delay.dwellMean().seconds(),
                delay.earlyLimit().toMillis() / 1000.0,
                delay.lateLimit().toMillis() / 1000.0,
                delay.minSpeedRatio(),
                routeFactor.phi(),
                routeFactor.sigma(),
                routeFactor.bucket());
    }

    public @Nullable String feedSha256() {
        String sha = feed.sha256();
        return sha == null || sha.isBlank() ? null : sha;
    }
}
