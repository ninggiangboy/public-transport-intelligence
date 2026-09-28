package dev.pti.simulator.motion;

import dev.pti.simulator.feed.ServiceDay;
import dev.pti.simulator.feed.ServiceDays;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The vehicles working their blocks around business time {@code t} (DOC-25 §3.3, §4.4): one {@link VehicleRun} per
 * {@code (service date, block)} from the block's scheduled start until the vehicle finishes it. A run that appears
 * mid-block, after a restart, is rebuilt deterministically from the block start. Not thread-safe; owned by the
 * emitter thread.
 */
public final class Fleet {

    private static final Comparator<Key> ORDER =
            Comparator.comparing(Key::serviceDate).thenComparingInt(Key::start).thenComparing(Key::blockId);

    private final ServiceDays serviceDays;
    private final DelayModel model;
    private final long maxLayoverEmitMillis;
    private final long lateMarginMillis;
    private final TreeMap<Key, VehicleRun> runs = new TreeMap<>(ORDER);
    private final Set<Key> retired = new HashSet<>();
    private int syntheticVehicles;

    /**
     * @param lateMargin how long after its scheduled end a block may still be running: the late limit of the delay
     *     model plus a margin
     */
    public Fleet(ServiceDays serviceDays, DelayModel model, Duration maxLayoverEmit, Duration lateMargin) {
        this.serviceDays = serviceDays;
        this.model = model;
        this.maxLayoverEmitMillis = maxLayoverEmit.toMillis();
        this.lateMarginMillis = lateMargin.toMillis();
    }

    /** Starts the blocks that have begun by {@code t} and drops the finished ones. */
    public void refresh(long t) {
        List<ServiceDay> days = serviceDays.around(Instant.ofEpochMilli(t));
        Set<LocalDate> dates = new HashSet<>();
        int synthetic = 0;
        for (ServiceDay day : days) {
            dates.add(day.serviceDate());
            synthetic += day.syntheticBuses();
            long base = serviceDays.instantOf(day.serviceDate(), 0).toEpochMilli();
            for (ServiceDay.AssignedBlock assigned : day.blocks()) {
                long start = base + assigned.block().start() * 1000L;
                if (start > t) {
                    break;
                }
                if (t > base + assigned.block().end() * 1000L + lateMarginMillis) {
                    continue;
                }
                Key key = new Key(
                        day.serviceDate(),
                        assigned.block().start(),
                        assigned.block().blockId());
                if (runs.containsKey(key) || retired.contains(key)) {
                    continue;
                }
                VehicleRun run = new VehicleRun(
                        assigned.block(),
                        assigned.vehicleId(),
                        day.serviceDate(),
                        base,
                        model,
                        maxLayoverEmitMillis,
                        t);
                if (run.done(t)) {
                    retired.add(key);
                } else {
                    runs.put(key, run);
                }
            }
        }
        syntheticVehicles = synthetic;
        runs.entrySet().removeIf(e -> {
            boolean gone =
                    !dates.contains(e.getKey().serviceDate()) || e.getValue().done(t);
            if (gone) {
                retired.add(e.getKey());
            }
            return gone;
        });
        retired.removeIf(key -> !dates.contains(key.serviceDate()));
        dates.stream().min(Comparator.naturalOrder()).ifPresent(model.routeFactors()::evictBefore);
    }

    /** The current runs, ordered by service date, block start and block id. */
    public List<VehicleRun> runs() {
        return new ArrayList<>(runs.values());
    }

    /** Blocks worked by synthetic vehicle ids on the current service dates (DOC-13 §4). */
    public int syntheticVehicles() {
        return syntheticVehicles;
    }

    private record Key(LocalDate serviceDate, int start, String blockId) {}
}
