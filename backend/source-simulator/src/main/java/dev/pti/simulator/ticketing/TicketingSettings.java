package dev.pti.simulator.ticketing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The ticketing parameters (DOC-25 §9, DOC-29 §3.2), bound from {@code pti.sim.ticketing}.
 *
 * @param rateProfile sales per second for each hour of the day, agency time (DOC-25 §9.2)
 * @param weekendFactor applied on Saturdays, Sundays and holidays
 */
public record TicketingSettings(
        @NotNull @Valid Fare fare,
        @NotEmpty Map<TicketType, Double> mix,
        @DecimalMin("0") @DecimalMax("1") double refundRatio,
        @DecimalMin("0") @DecimalMax("1") double voidRatio,
        @DecimalMin("0") @DecimalMax("1") double deleteRatio,
        @Min(0) @Max(999) int kioskCount,
        @NotNull @Size(min = 24, max = 24) List<@NotNull @DecimalMin("0") Double> rateProfile,
        @DecimalMin("0") double weekendFactor,
        @Min(1) int customerPool) {

    public TicketingSettings {
        mix = Map.copyOf(mix);
        rateProfile = List.copyOf(rateProfile);
    }

    /** US dollars (DOC-13 §5.4). */
    public record Fare(
            @NotNull @DecimalMin("0") BigDecimal single,
            @NotNull @DecimalMin("0") BigDecimal singlePeak,
            @NotNull @DecimalMin("0") BigDecimal day,
            @NotNull @DecimalMin("0") BigDecimal month) {}
}
