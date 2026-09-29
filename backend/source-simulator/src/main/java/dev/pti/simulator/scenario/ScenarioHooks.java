package dev.pti.simulator.scenario;

import dev.pti.simulator.emit.MessageInterceptor;
import dev.pti.simulator.emit.OutboundMessage;
import dev.pti.simulator.emit.VehicleMarks;
import dev.pti.simulator.motion.SegmentOverlay;
import dev.pti.simulator.motion.TripRun;
import dev.pti.simulator.ticketing.TicketingActions;
import dev.pti.simulator.ticketing.TicketingOverlay;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;

/**
 * Where running scenarios attach to the simulator (DOC-25 §7.1): segment overlays and vehicle marks on the emitter,
 * message interceptors on the sending path, ticketing overlays on the seeder. Registration is thread-safe; each hook
 * is called on its component's own thread. A hook that throws is removed together with every other hook of its run,
 * and the failure is reported, so the simulator carries on without the scenario.
 */
public final class ScenarioHooks implements SegmentOverlay, VehicleMarks, Supplier<List<MessageInterceptor>> {

    /** Interceptor order (DOC-25 §7.1): resends are taken from the clean message, before it is corrupted. */
    public static final int DUPLICATES_ORDER = 10;

    public static final int BAD_DATA_ORDER = 20;

    private final List<Hook<SegmentOverlay>> overlays = new CopyOnWriteArrayList<>();
    private final List<Hook<RunMarks>> marks = new CopyOnWriteArrayList<>();
    private final List<Hook<MessageInterceptor>> interceptors = new CopyOnWriteArrayList<>();
    /** Serializes changes to {@link #interceptors} with the rebuild of the ordered chain. */
    private final Object chainLock = new Object();

    private final List<Hook<TicketingOverlay>> ticketing = new CopyOnWriteArrayList<>();
    private volatile List<MessageInterceptor> interceptorChain = List.of();
    private volatile BiConsumer<UUID, Throwable> onFailure = (runId, e) -> {};

    /** Where failures of a hook go; the engine ends the run as {@code FAILED}. */
    public void onFailure(BiConsumer<UUID, Throwable> listener) {
        this.onFailure = listener;
    }

    public Registration addOverlay(UUID runId, SegmentOverlay overlay) {
        return add(overlays, new Hook<>(runId, 0, overlay));
    }

    public Registration addMarks(UUID runId, RunMarks runMarks) {
        return add(marks, new Hook<>(runId, 0, runMarks));
    }

    public Registration addInterceptor(UUID runId, int order, MessageInterceptor interceptor) {
        Hook<MessageInterceptor> hook = new Hook<>(runId, order, interceptor);
        synchronized (chainLock) {
            interceptors.add(hook);
            rebuildChain();
        }
        return () -> {
            synchronized (chainLock) {
                interceptors.remove(hook);
                rebuildChain();
            }
        };
    }

    public Registration addTicketing(UUID runId, TicketingOverlay overlay) {
        return add(ticketing, new Hook<>(runId, 0, overlay));
    }

    /** Removes every hook of a run. */
    public void removeAll(UUID runId) {
        overlays.removeIf(h -> h.runId().equals(runId));
        marks.removeIf(h -> h.runId().equals(runId));
        ticketing.removeIf(h -> h.runId().equals(runId));
        synchronized (chainLock) {
            interceptors.removeIf(h -> h.runId().equals(runId));
            rebuildChain();
        }
    }

    /** Whether any hook of the run is still attached, e.g. a refund burst draining its pending refunds. */
    public boolean attached(UUID runId) {
        return overlays.stream().anyMatch(h -> h.runId().equals(runId))
                || marks.stream().anyMatch(h -> h.runId().equals(runId))
                || ticketing.stream().anyMatch(h -> h.runId().equals(runId))
                || interceptors.stream().anyMatch(h -> h.runId().equals(runId));
    }

    @Override
    public double segmentDelta(TripRun run, int fromIndex, long departureMillis, double departureDelay) {
        double sum = 0;
        for (Hook<SegmentOverlay> h : overlays) {
            try {
                sum += h.hook().segmentDelta(run, fromIndex, departureMillis, departureDelay);
            } catch (RuntimeException e) {
                fail(h.runId(), e);
            }
        }
        return sum;
    }

    @Override
    public double dwellDelta(TripRun run, int stopIndex, long arrivalMillis) {
        double sum = 0;
        for (Hook<SegmentOverlay> h : overlays) {
            try {
                sum += h.hook().dwellDelta(run, stopIndex, arrivalMillis);
            } catch (RuntimeException e) {
                fail(h.runId(), e);
            }
        }
        return sum;
    }

    @Override
    public boolean skipped(TripRun run, int stopIndex) {
        for (Hook<SegmentOverlay> h : overlays) {
            try {
                if (h.hook().skipped(run, stopIndex)) {
                    return true;
                }
            } catch (RuntimeException e) {
                fail(h.runId(), e);
            }
        }
        return false;
    }

    @Override
    public @Nullable UUID runIdFor(TripRun run, long t) {
        for (Hook<RunMarks> h : marks) {
            try {
                if (h.hook().marks(run, t)) {
                    return h.runId();
                }
            } catch (RuntimeException e) {
                fail(h.runId(), e);
            }
        }
        return null;
    }

    @Override
    public void sweep(long t) {
        for (Hook<RunMarks> h : marks) {
            try {
                h.hook().sweep(t);
            } catch (RuntimeException e) {
                fail(h.runId(), e);
            }
        }
    }

    /** The interceptors in their fixed order, for {@link dev.pti.simulator.emit.InterceptingSink}. */
    @Override
    public List<MessageInterceptor> get() {
        return interceptorChain;
    }

    /** The running ticketing overlays; one that reports it is done is removed. */
    public List<TicketingOverlay> ticketing() {
        if (ticketing.isEmpty()) {
            return List.of();
        }
        return ticketing.stream().<TicketingOverlay>map(this::guardedTicketing).toList();
    }

    private TicketingOverlay guardedTicketing(Hook<TicketingOverlay> h) {
        return (TicketingActions actions, long from, long now) -> {
            try {
                boolean more = h.hook().onTick(actions, from, now);
                if (!more) {
                    ticketing.remove(h);
                }
                return more;
            } catch (DataAccessException e) {
                // The ticketing database is down: the seeder drops the tick and retries (DOC-25 §9.4).
                throw e;
            } catch (RuntimeException e) {
                fail(h.runId(), e);
                return false;
            }
        };
    }

    private void rebuildChain() {
        interceptorChain = interceptors.stream()
                .sorted(Comparator.comparingInt(Hook<MessageInterceptor>::order))
                .<MessageInterceptor>map(this::guardedInterceptor)
                .toList();
    }

    private MessageInterceptor guardedInterceptor(Hook<MessageInterceptor> h) {
        return (OutboundMessage message, long businessNow) -> {
            try {
                return h.hook().intercept(message, businessNow);
            } catch (RuntimeException e) {
                fail(h.runId(), e);
                return List.of(message);
            }
        };
    }

    private void fail(UUID runId, RuntimeException e) {
        removeAll(runId);
        onFailure.accept(runId, e);
    }

    private static <T> Registration add(List<Hook<T>> list, Hook<T> hook) {
        list.add(hook);
        return () -> list.remove(hook);
    }

    /** Detaches one hook; idempotent. */
    @FunctionalInterface
    public interface Registration {
        void remove();
    }

    /** Which trips a scenario run acts on, so that their messages carry the run id (DOC-25 §7.1). */
    public interface RunMarks {

        /** Whether the run acts on {@code run} at business time {@code t}. */
        boolean marks(TripRun run, long t);

        /** Called at the end of every emitter tick. */
        default void sweep(long t) {}
    }

    private record Hook<T>(UUID runId, int order, T hook) {}
}
