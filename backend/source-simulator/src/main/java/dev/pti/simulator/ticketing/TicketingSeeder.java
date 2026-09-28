package dev.pti.simulator.ticketing;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.Throughput;
import dev.pti.simulator.motion.Seeds;
import dev.pti.simulator.rate.RateControl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Writes simulated ticket sales into {@code ticketing_source} (DOC-25 §9): a Poisson process at
 * {@code λ(hour) × dayFactor × rateMultiplier.ticketing}, plus refunds, voids and deletes some time after a sale.
 * Pending follow-ups live in memory and are lost on restart. Not thread-safe; runs on the {@code sim-ticketing} thread.
 */
public final class TicketingSeeder {

    private static final Logger log = LoggerFactory.getLogger(TicketingSeeder.class);

    /** A tick never makes up for more than this after a stall. */
    static final long MAX_BACKLOG_MILLIS = 30_000;

    private static final long WARN_EVERY_MILLIS = 10_000;

    /** The {@link Throughput} key of inserted sales. */
    public static final String SALES = "sales";

    private final BusinessClock clock;
    private final SaleGenerator generator;
    private final SalePointCatalog catalog;
    private final TicketingRepository repository;
    private final RateControl rate;
    private final TicketingSettings settings;
    private final MeterRegistry registry;
    private final Throughput throughput;
    private final SplittableRandom rng;
    private final PriorityQueue<FollowUp> followUps =
            new PriorityQueue<>(Comparator.comparingLong(FollowUp::dueMillis));
    private final Counter errors;
    private boolean salePointsReady;
    private long previous = Long.MIN_VALUE;
    private long lastWarning = Long.MIN_VALUE;

    public TicketingSeeder(
            BusinessClock clock,
            SaleGenerator generator,
            SalePointCatalog catalog,
            TicketingRepository repository,
            RateControl rate,
            TicketingSettings settings,
            long seed,
            MeterRegistry registry,
            Throughput throughput) {
        this.clock = clock;
        this.generator = generator;
        this.catalog = catalog;
        this.repository = repository;
        this.rate = rate;
        this.settings = settings;
        this.registry = registry;
        this.throughput = throughput;
        this.rng = new SplittableRandom(Seeds.of(seed, "ticketing"));
        this.errors = Counter.builder("pti.sim.ticketing.errors").register(registry);
    }

    public void tick() {
        tickUntil(clock.millis());
    }

    /** Writes what is due in {@code (previous tick, now]}. Visible for tests driving a manual clock. */
    public void tickUntil(long now) {
        long from = previous == Long.MIN_VALUE ? now : Math.max(previous, now - MAX_BACKLOG_MILLIS);
        previous = now;
        try {
            if (!salePointsReady) {
                int inserted = repository.upsertSalePoints(catalog.all());
                log.info(
                        "Sale points seeded: inserted={} total={}",
                        inserted,
                        catalog.all().size());
                salePointsReady = true;
            }
            runFollowUps(now);
            sell(from, now);
        } catch (DataAccessException e) {
            // The ticketing system is "down": this tick's sales are lost, the next tick tries again (DOC-25 §9.4).
            errors.increment();
            if (lastWarning == Long.MIN_VALUE || now - lastWarning >= WARN_EVERY_MILLIS) {
                lastWarning = now;
                log.warn("Ticketing write failed, dropping this tick's transactions: {}", e.toString());
            }
        }
    }

    private void sell(long from, long now) {
        double multiplier = rate.ticketing();
        if (multiplier == 0 || now <= from) {
            return;
        }
        double mean = generator.rate(Instant.ofEpochMilli(from)) * multiplier * (now - from) / 1000.0;
        long[] times = new long[poisson(mean)];
        for (int i = 0; i < times.length; i++) {
            times[i] = from + 1 + rng.nextLong(now - from);
        }
        Arrays.sort(times);
        for (long t : times) {
            Transaction sale = generator.sale(rng, Instant.ofEpochMilli(t));
            repository.insert(sale);
            counter("SALE", "insert").increment();
            throughput.record(SALES);
            scheduleFollowUp(sale, t);
        }
    }

    /** At most one follow-up per sale: refund, void or delete (DOC-25 §9.3). */
    private void scheduleFollowUp(Transaction sale, long t) {
        double u = rng.nextDouble();
        if (u < settings.refundRatio()) {
            followUps.add(new FollowUp(t + rng.nextLong(60_000, 1_800_001), Action.REFUND, sale));
        } else if (u < settings.refundRatio() + settings.voidRatio()) {
            followUps.add(new FollowUp(t + rng.nextLong(1_000, 5_001), Action.VOID, sale));
        } else if (u < settings.refundRatio() + settings.voidRatio() + settings.deleteRatio()) {
            followUps.add(new FollowUp(t + rng.nextLong(60_000, 300_001), Action.DELETE, sale));
        }
    }

    private void runFollowUps(long now) {
        while (!followUps.isEmpty() && followUps.peek().dueMillis() <= now) {
            FollowUp next = followUps.poll();
            Counter done =
                    switch (next.action()) {
                        case REFUND -> {
                            UUID refundId = UuidCreator.getTimeOrderedEpoch();
                            repository.insert(next.sale().refund(refundId, Instant.ofEpochMilli(next.dueMillis())));
                            yield counter("REFUND", "insert");
                        }
                        case VOID -> {
                            repository.voidTransaction(next.sale().id());
                            yield counter("SALE", "void");
                        }
                        case DELETE -> {
                            repository.delete(next.sale().id());
                            yield counter("SALE", "delete");
                        }
                    };
            done.increment();
        }
    }

    /** Knuth's method for the usual few sales per tick; a normal approximation after a stall or at high rates. */
    private int poisson(double mean) {
        if (mean > 30) {
            return (int) Math.max(0, Math.round(rng.nextGaussian(mean, Math.sqrt(mean))));
        }
        double limit = Math.exp(-mean);
        double p = rng.nextDouble();
        int n = 0;
        while (p > limit) {
            p *= rng.nextDouble();
            n++;
        }
        return n;
    }

    private Counter counter(String txnType, String action) {
        return Counter.builder("pti.sim.ticket.transactions")
                .tag("txn_type", txnType)
                .tag("action", action)
                .register(registry);
    }

    public int pendingFollowUps() {
        return followUps.size();
    }

    private enum Action {
        REFUND,
        VOID,
        DELETE
    }

    private record FollowUp(long dueMillis, Action action, Transaction sale) {}
}
