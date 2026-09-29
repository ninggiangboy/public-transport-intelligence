package dev.pti.etl.write;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.RuleViolationException;
import dev.pti.etl.core.TripUpdateRow;
import dev.pti.etl.core.WriteSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DQ-02 (DOC-16 §2.1): several records of one chunk with the same key. The newest version wins and the others are
 * collapsed; two different payloads at the newest version are a producer error, so the later one (by offset) wins
 * and the others are rejected.
 *
 * <p>A TripUpdate is keyed per stop, {@code (service_date, trip_id, stop_sequence)}: two TripUpdates of one trip in
 * a chunk usually share most stops, and the older one keeps the stops the newer one no longer reports. It is
 * collapsed only when every one of its stops is superseded. Other sources have one key per message.
 */
public final class DuplicateKeyRule {

    public static final String ID = "DQ-02";

    private static final Comparator<Candidate> LATEST = Comparator.comparingLong((Candidate c) -> c.item.version())
            .thenComparingLong(c -> c.item.offsetOrZero())
            .thenComparingInt(c -> c.index);

    public ChunkRuleResult apply(List<WriteSet> items) {
        Map<String, List<Candidate>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            WriteSet item = items.get(i);
            for (String key : keys(item)) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new Candidate(i, item));
            }
        }
        if (byKey.values().stream().allMatch(c -> c.size() == 1)) {
            return ChunkRuleResult.keepAll(items);
        }

        Map<Integer, RuleViolationException> conflicts = new LinkedHashMap<>();
        byKey.forEach((key, candidates) -> findConflicts(key, candidates, conflicts));

        List<Set<String>> won = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            won.add(new HashSet<>());
        }
        byKey.forEach((key, candidates) -> candidates.stream()
                .filter(c -> !conflicts.containsKey(c.index))
                .max(LATEST)
                .ifPresent(winner -> won.get(winner.index).add(key)));

        List<WriteSet> kept = new ArrayList<>();
        List<ChunkRuleResult.Rejected> rejected = new ArrayList<>();
        int collapsed = 0;
        for (int i = 0; i < items.size(); i++) {
            WriteSet item = items.get(i);
            RuleViolationException conflict = conflicts.get(i);
            Set<String> keys = won.get(i);
            if (conflict != null) {
                rejected.add(new ChunkRuleResult.Rejected(item, conflict));
            } else if (keys.isEmpty() && !item.isEmpty()) {
                collapsed++;
            } else if (!item.tripUpdates().isEmpty()
                    && keys.size() < item.tripUpdates().size()) {
                List<TripUpdateRow> rows = item.tripUpdates().stream()
                        .filter(r -> keys.contains(r.key()))
                        .toList();
                kept.add(item.withTripUpdates(rows));
            } else {
                kept.add(item);
            }
        }
        return new ChunkRuleResult(kept, rejected, collapsed);
    }

    /** Among the candidates at the newest version, those whose payload differs from the latest one conflict. */
    private static void findConflicts(
            String key, List<Candidate> candidates, Map<Integer, RuleViolationException> conflicts) {
        if (candidates.size() < 2) {
            return;
        }
        Candidate latest = candidates.stream().max(LATEST).orElseThrow();
        for (Candidate c : candidates) {
            if (c.item.version() == latest.item.version()
                    && !c.item.messageHash().equals(latest.item.messageHash())) {
                conflicts.putIfAbsent(
                        c.index,
                        new RuleViolationException(
                                DlqStage.DEDUP,
                                ID,
                                "Conflicting payloads for key " + key
                                        + " at the same version; the record at offset "
                                        + latest.item.offsetOrZero() + " wins"));
            }
        }
    }

    private static List<String> keys(WriteSet item) {
        if (!item.tripUpdates().isEmpty()) {
            return item.tripUpdates().stream().map(TripUpdateRow::key).toList();
        }
        return item.isEmpty() ? List.of() : List.of(item.source() + "|" + item.businessKey());
    }

    private record Candidate(int index, WriteSet item) {}
}
