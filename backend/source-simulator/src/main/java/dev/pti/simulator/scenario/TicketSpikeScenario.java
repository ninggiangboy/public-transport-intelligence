package dev.pti.simulator.scenario;

import dev.pti.simulator.motion.Seeds;
import dev.pti.simulator.ticketing.Poisson;
import dev.pti.simulator.ticketing.SalePoint;
import dev.pti.simulator.ticketing.SalePointCatalog;
import dev.pti.simulator.ticketing.TicketingActions;
import dev.pti.simulator.ticketing.TicketingOverlay;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;

/**
 * {@code ticket-spike} (DOC-25 §7.6, FR-09.4, DR-34): extra sales at one sale point, a Poisson process of
 * {@code extraPerMinute}, on top of the regular ones. Nothing is sold while ticketing is paused.
 */
public final class TicketSpikeScenario implements Scenario<TicketSpikeScenario.Params> {

    public static final String NAME = "ticket-spike";

    public record Params(
            @ScenarioParam(label = "Sale point", salePointSuggestions = true) @NotNull @Size(min = 1, max = 64)
            String salePointId,

            @ScenarioParam(label = "Extra sales per minute") @NotNull @DecimalMin("1") @DecimalMax("120")
            Double extraPerMinute,

            @ScenarioParam(label = "Duration") @NotNull @DurationMin(minutes = 15)
            Duration duration) {}

    private final SalePointCatalog catalog;

    public TicketSpikeScenario(SalePointCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Ticket sales spike";
    }

    @Override
    public String description() {
        return "Add extra sales at one sale point.";
    }

    @Override
    public Concurrency concurrency() {
        return Concurrency.PER_TARGET;
    }

    @Override
    public Class<Params> paramsType() {
        return Params.class;
    }

    @Override
    public Params defaults() {
        return new Params(catalog.busiest().id(), 6.0, Duration.ofMinutes(30));
    }

    @Override
    public List<ScenarioException.FieldError> check(Params params) {
        return SalePoints.check(catalog, params.salePointId());
    }

    @Override
    public @Nullable String target(Params params) {
        return params.salePointId();
    }

    @Override
    public Duration duration(Params params) {
        return params.duration();
    }

    @Override
    public ScenarioHandle start(ScenarioContext context, Params params) {
        SalePoint point = catalog.find(params.salePointId()).orElseThrow();
        Spike spike = new Spike(point, params.extraPerMinute(), SalePoints.random(context, NAME));
        ScenarioHooks.Registration registration = context.hooks().addTicketing(context.runId(), spike);
        return new ScenarioHandle() {
            @Override
            public void stop() {
                spike.stopped = true;
                registration.remove();
            }

            @Override
            public Map<String, Object> progress() {
                return Map.of("salePointId", point.id(), "sales", spike.sales.get());
            }
        };
    }

    private static final class Spike implements TicketingOverlay {

        private final SalePoint point;
        private final double perMinute;
        private final SplittableRandom rng;
        private final AtomicLong sales = new AtomicLong();
        private volatile boolean stopped;

        Spike(SalePoint point, double perMinute, SplittableRandom rng) {
            this.point = point;
            this.perMinute = perMinute;
            this.rng = rng;
        }

        @Override
        public boolean onTick(TicketingActions actions, long from, long now) {
            if (stopped) {
                return false;
            }
            if (actions.paused() || now <= from) {
                return true;
            }
            long[] times = new long[Poisson.sample(rng, perMinute * (now - from) / 60_000.0)];
            for (int i = 0; i < times.length; i++) {
                times[i] = from + 1 + rng.nextLong(now - from);
            }
            Arrays.sort(times);
            for (long t : times) {
                actions.insert(actions.generator().saleAt(rng, point, Instant.ofEpochMilli(t)));
                sales.incrementAndGet();
            }
            return true;
        }
    }

    /** Helpers shared with {@link RefundBurstScenario}. */
    static final class SalePoints {

        private SalePoints() {}

        static List<ScenarioException.FieldError> check(SalePointCatalog catalog, String salePointId) {
            return catalog.find(salePointId).isPresent()
                    ? List.of()
                    : List.of(new ScenarioException.FieldError("salePointId", "unknown sale point"));
        }

        static SplittableRandom random(ScenarioContext context, String salt) {
            return new SplittableRandom(Seeds.of(
                    context.seed(),
                    salt,
                    context.runId().getMostSignificantBits(),
                    context.runId().getLeastSignificantBits()));
        }
    }
}
