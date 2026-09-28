package dev.pti.simulator;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Events per second by key, averaged over the last ten whole seconds of real time, for {@code GET /sim/status}
 * (DOC-25 §8). Prometheus has the exact counters; this is only what the status page shows.
 */
public final class Throughput {

    static final int WINDOW_SECONDS = 10;

    private final LongSupplier realMillis;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public Throughput(LongSupplier realMillis) {
        this.realMillis = realMillis;
    }

    public void record(String key) {
        windows.computeIfAbsent(key, k -> new Window()).add(second());
    }

    public double perSecond(String key) {
        Window window = windows.get(key);
        return window == null ? 0 : window.perSecond(second());
    }

    /** Every key seen so far, sorted. */
    public Map<String, Double> perSecond() {
        long now = second();
        Map<String, Double> result = new TreeMap<>();
        windows.forEach((key, window) -> result.put(key, window.perSecond(now)));
        return result;
    }

    private long second() {
        return Math.floorDiv(realMillis.getAsLong(), 1000);
    }

    /** A ring of per-second counts; the current, partial second is not reported. */
    private static final class Window {

        private final long[] seconds = new long[WINDOW_SECONDS + 1];
        private final long[] counts = new long[WINDOW_SECONDS + 1];

        synchronized void add(long second) {
            int i = (int) Math.floorMod(second, (long) seconds.length);
            if (seconds[i] != second) {
                seconds[i] = second;
                counts[i] = 0;
            }
            counts[i]++;
        }

        synchronized double perSecond(long now) {
            long sum = 0;
            for (int i = 0; i < seconds.length; i++) {
                if (seconds[i] >= now - WINDOW_SECONDS && seconds[i] < now) {
                    sum += counts[i];
                }
            }
            return Math.round(sum * 10.0 / WINDOW_SECONDS) / 10.0;
        }
    }
}
