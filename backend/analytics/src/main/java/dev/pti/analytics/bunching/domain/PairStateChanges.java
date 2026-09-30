package dev.pti.analytics.bunching.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of {@code analytics_bunching_pair_state} to write after a run: what differs between the rows that were
 * loaded and the states the machine ended with. A row that did not change is not written.
 *
 * @param upserts states that are new or changed
 * @param deletes loaded states that are gone
 */
public record PairStateChanges(List<PairState> upserts, List<PairState> deletes) {

    public PairStateChanges {
        upserts = List.copyOf(upserts);
        deletes = List.copyOf(deletes);
    }

    /**
     * Compares by the primary key of the table: direction and both vehicles. The same two vehicles in another
     * direction are another row: the old one is deleted and the new one inserted.
     */
    public static PairStateChanges between(Collection<PairState> loaded, Collection<PairState> current) {
        Map<RowKey, PairState> before = index(loaded);
        Map<RowKey, PairState> after = index(current);
        List<PairState> upserts = after.entrySet().stream()
                .filter(e -> !e.getValue().equals(before.get(e.getKey())))
                .map(Map.Entry::getValue)
                .toList();
        List<PairState> deletes = before.entrySet().stream()
                .filter(e -> !after.containsKey(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        return new PairStateChanges(upserts, deletes);
    }

    public boolean isEmpty() {
        return upserts.isEmpty() && deletes.isEmpty();
    }

    private static Map<RowKey, PairState> index(Collection<PairState> states) {
        Map<RowKey, PairState> byKey = new HashMap<>();
        for (PairState state : states) {
            byKey.put(new RowKey(state.directionId(), state.leader(), state.follower()), state);
        }
        return byKey;
    }

    private record RowKey(int directionId, String leader, String follower) {}
}
