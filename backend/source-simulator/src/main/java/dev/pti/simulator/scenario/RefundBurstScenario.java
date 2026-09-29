package dev.pti.simulator.scenario;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.simulator.ticketing.Poisson;
import dev.pti.simulator.ticketing.SalePoint;
import dev.pti.simulator.ticketing.SalePointCatalog;
import dev.pti.simulator.ticketing.TicketingActions;
import dev.pti.simulator.ticketing.TicketingOverlay;
import dev.pti.simulator.ticketing.Transaction;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;

/**
 * {@code refund-burst} (DOC-25 §7.7, FR-09.4): sales at one sale point, {@code refundRatio} of which are refunded
 * {@code refundDelay} later. Refunds always point at a real sale; those still pending when the run ends are made
 * anyway, then the overlay detaches.
 */
public final class RefundBurstScenario implements Scenario<RefundBurstScenario.Params> {

    public static final String NAME = "refund-burst";

    public record Params(
            @ScenarioParam(label = "Sale point", salePointSuggestions = true) @NotNull @Size(min = 1, max = 64)
            String salePointId,

            @ScenarioParam(label = "Sales per minute") @NotNull @DecimalMin("0.5") @DecimalMax("60")
            Double salesPerMinute,

            @ScenarioParam(label = "Share refunded") @NotNull @DecimalMin("0.31") @DecimalMax("1.0")
            Double refundRatio,

            @ScenarioParam(label = "Refund delay") @NotNull @DurationMin(seconds = 0) @DurationMax(minutes = 30)
            Duration refundDelay,

            @ScenarioParam(label = "Duration") @NotNull @DurationMin(minutes = 15)
            Duration duration) {}

    private final SalePointCatalog catalog;

    public RefundBurstScenario(SalePointCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Refund burst";
    }

    @Override
    public String description() {
        return "Sell tickets at one sale point and refund most of them soon after.";
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
        return new Params(catalog.busiest().id(), 2.0, 0.8, Duration.ofMinutes(2), Duration.ofMinutes(30));
    }

    @Override
    public List<ScenarioException.FieldError> check(Params params) {
        return TicketSpikeScenario.SalePoints.check(catalog, params.salePointId());
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
        Burst burst = new Burst(
                point,
                params.salesPerMinute(),
                params.refundRatio(),
                params.refundDelay().toMillis(),
                TicketSpikeScenario.SalePoints.random(context, NAME));
        context.hooks().addTicketing(context.runId(), burst);
        return new ScenarioHandle() {
            @Override
            public void stop() {
                burst.stopped = true;
            }

            @Override
            public Map<String, Object> progress() {
                return Map.of(
                        "salePointId", point.id(),
                        "sales", burst.sales.get(),
                        "refunds", burst.refunds.get(),
                        "pendingRefunds", burst.pending.get());
            }
        };
    }

    /** Runs on the {@code sim-ticketing} thread; the counters are read by the API. */
    private static final class Burst implements TicketingOverlay {

        private final SalePoint point;
        private final double perMinute;
        private final double refundRatio;
        private final long refundDelayMillis;
        private final SplittableRandom rng;
        private final Deque<Refund> queue = new ArrayDeque<>();
        private final AtomicLong sales = new AtomicLong();
        private final AtomicLong refunds = new AtomicLong();
        private final AtomicLong pending = new AtomicLong();
        private volatile boolean stopped;

        Burst(SalePoint point, double perMinute, double refundRatio, long refundDelayMillis, SplittableRandom rng) {
            this.point = point;
            this.perMinute = perMinute;
            this.refundRatio = refundRatio;
            this.refundDelayMillis = refundDelayMillis;
            this.rng = rng;
        }

        @Override
        public boolean onTick(TicketingActions actions, long from, long now) {
            // Refunds come first: they are due in time order, and a sale is always older than its refund.
            while (!queue.isEmpty() && queue.peekFirst().dueMillis() <= now) {
                Refund refund = queue.pollFirst();
                actions.insert(refund.sale()
                        .refund(UuidCreator.getTimeOrderedEpoch(), Instant.ofEpochMilli(refund.dueMillis())));
                refunds.incrementAndGet();
                pending.decrementAndGet();
            }
            if (stopped) {
                return !queue.isEmpty();
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
                Transaction sale = actions.generator().saleAt(rng, point, Instant.ofEpochMilli(t));
                actions.insert(sale);
                sales.incrementAndGet();
                if (rng.nextDouble() < refundRatio) {
                    queue.addLast(new Refund(t + refundDelayMillis, sale));
                    pending.incrementAndGet();
                }
            }
            return true;
        }
    }

    private record Refund(long dueMillis, Transaction sale) {}
}
