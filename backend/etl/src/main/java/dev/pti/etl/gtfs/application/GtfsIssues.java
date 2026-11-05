package dev.pti.etl.gtfs.application;

import dev.pti.etl.gtfs.domain.FeedCheck;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts and samples per feed check, the {@code errors}/{@code warnings} entries of the validation report
 * (DOC-21 §4). Kept as JSON text in the execution context, so that a restart does not lose the counts. A sample is a
 * plain map; turning it into JSON is the job of the report codec in the adapter.
 */
public final class GtfsIssues {

    private final int maxSamples;
    private final Map<FeedCheck, Long> counts = new LinkedHashMap<>();
    private final Map<FeedCheck, List<Map<String, Object>>> samples = new LinkedHashMap<>();

    public GtfsIssues(int maxSamples) {
        this.maxSamples = maxSamples;
    }

    /** A new, empty sample: an ordered map the caller fills in. */
    public static Map<String, Object> sample() {
        return new LinkedHashMap<>();
    }

    public void add(FeedCheck check, Map<String, Object> sample) {
        add(check, 1, List.of(sample));
    }

    public void add(FeedCheck check, long count, List<Map<String, Object>> newSamples) {
        if (count <= 0) {
            return;
        }
        counts.merge(check, count, Long::sum);
        List<Map<String, Object>> kept = samples.computeIfAbsent(check, c -> new ArrayList<>());
        for (Map<String, Object> s : newSamples) {
            if (kept.size() < maxSamples) {
                kept.add(s);
            }
        }
    }

    public void addAll(GtfsIssues other) {
        other.counts.forEach((check, count) -> add(check, count, other.samples.getOrDefault(check, List.of())));
    }

    public long count(FeedCheck check) {
        return counts.getOrDefault(check, 0L);
    }

    public boolean hasErrors() {
        return counts.keySet().stream().anyMatch(FeedCheck::error);
    }

    public List<FeedCheck> checks() {
        return List.copyOf(counts.keySet());
    }

    /** The kept samples of one check. */
    public List<Map<String, Object>> samples(FeedCheck check) {
        return samples.getOrDefault(check, List.of());
    }
}
