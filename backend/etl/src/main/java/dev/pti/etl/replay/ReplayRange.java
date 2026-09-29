package dev.pti.etl.replay;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The record-time window {@code [from, to)} of a raw zone replay (DOC-22 §4.1) and the hour directories that hold it:
 * the sink files each record under the hour of its own CreateTime, so no neighbouring hour is needed.
 */
public record ReplayRange(Instant from, Instant to) {

    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("'dt='yyyy-MM-dd'/hh='HH").withZone(ZoneOffset.UTC);

    public ReplayRange {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("fromTs must be before toTs: " + from + " / " + to);
        }
    }

    public boolean contains(Instant recordTime) {
        return !recordTime.isBefore(from) && recordTime.isBefore(to);
    }

    /** {@code <topic>/dt=…/hh=…/} for every hour from {@code floor_hour(from)} to {@code floor_hour(to − 1 ms)}. */
    public List<String> hourPrefixes(String topic) {
        List<String> prefixes = new ArrayList<>();
        Instant last = to.minusMillis(1).truncatedTo(ChronoUnit.HOURS);
        for (Instant h = from.truncatedTo(ChronoUnit.HOURS); !h.isAfter(last); h = h.plus(1, ChronoUnit.HOURS)) {
            prefixes.add(topic + "/" + HOUR.format(h) + "/");
        }
        return prefixes;
    }
}
