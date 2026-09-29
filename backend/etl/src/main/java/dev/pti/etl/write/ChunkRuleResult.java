package dev.pti.etl.write;

import dev.pti.common.error.RuleViolationException;
import dev.pti.etl.core.WriteSet;
import java.util.List;

/**
 * Outcome of a rule applied to a whole chunk (DOC-16 §5).
 *
 * @param kept the items to write, in chunk order
 * @param rejected the items that go to the dead-letter queue
 * @param collapsed messages dropped because another message of the chunk supersedes them (not an error)
 */
public record ChunkRuleResult(List<WriteSet> kept, List<Rejected> rejected, int collapsed) {

    public ChunkRuleResult {
        kept = List.copyOf(kept);
        rejected = List.copyOf(rejected);
    }

    public static ChunkRuleResult keepAll(List<WriteSet> items) {
        return new ChunkRuleResult(items, List.of(), 0);
    }

    public record Rejected(WriteSet item, RuleViolationException violation) {}
}
