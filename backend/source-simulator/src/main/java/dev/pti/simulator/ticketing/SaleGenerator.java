package dev.pti.simulator.ticketing;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.common.time.DayType;
import dev.pti.simulator.motion.Period;
import dev.pti.simulator.motion.Seeds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.random.RandomGenerator;

/** Draws the content of a sale (DOC-25 §9.3) and the sales rate of an hour (§9.2). */
public final class SaleGenerator {

    /** Kiosk and onboard sales paid by card, which carry a customer reference; the rest is cash. */
    private static final double CARD_SHARE = 0.6;

    private final SalePointCatalog catalog;
    private final TicketingSettings settings;
    private final ZoneId zone;
    private final long seed;
    private final double[] mixCumulative;
    private final TicketType[] mixTypes = TicketType.values();

    public SaleGenerator(SalePointCatalog catalog, TicketingSettings settings, ZoneId zone, long seed) {
        this.catalog = catalog;
        this.settings = settings;
        this.zone = zone;
        this.seed = seed;
        this.mixCumulative = new double[mixTypes.length];
        double sum = 0;
        for (int i = 0; i < mixTypes.length; i++) {
            sum += settings.mix().getOrDefault(mixTypes[i], 0.0);
            mixCumulative[i] = sum;
        }
    }

    /** Sales per second at {@code t} before the rate multiplier: {@code λ(hour) × dayFactor}. */
    public double rate(Instant t) {
        ZonedDateTime local = t.atZone(zone);
        double dayFactor = DayType.of(local.toLocalDate()) == DayType.WEEKDAY ? 1.0 : settings.weekendFactor();
        return settings.rateProfile().get(local.getHour()) * dayFactor;
    }

    public Transaction sale(RandomGenerator rng, Instant at) {
        SalePoint point = catalog.pick(rng);
        TicketType type = ticketType(rng);
        boolean identified = point.kind() == SalePoint.Kind.APP || rng.nextDouble() < CARD_SHARE;
        return new Transaction(
                UuidCreator.getTimeOrderedEpoch(),
                point.id(),
                point.routeId(),
                point.stopId(),
                type,
                amount(type, at),
                null,
                identified ? customer(rng.nextInt(settings.customerPool())) : null,
                at);
    }

    private TicketType ticketType(RandomGenerator rng) {
        double u = rng.nextDouble() * mixCumulative[mixCumulative.length - 1];
        for (int i = 0; i < mixTypes.length; i++) {
            if (u < mixCumulative[i]) {
                return mixTypes[i];
            }
        }
        return TicketType.SINGLE;
    }

    /** Peak single fares apply 06:00–09:00 and 15:00–18:30 on weekdays, business time (DOC-13 §5.4). */
    BigDecimal amount(TicketType type, Instant at) {
        TicketingSettings.Fare fare = settings.fare();
        return switch (type) {
            case SINGLE -> Period.at(at.atZone(zone)) == Period.PEAK ? fare.singlePeak() : fare.single();
            case DAY -> fare.day();
            case MONTH -> fare.month();
        };
    }

    /** {@code cust-<8 hex>}, one of {@code customer-pool} simulated customers (DOC-25 §9.3). */
    String customer(int index) {
        return "cust-%08x".formatted(Seeds.of(seed, "customer", index) & 0xffff_ffffL);
    }
}
