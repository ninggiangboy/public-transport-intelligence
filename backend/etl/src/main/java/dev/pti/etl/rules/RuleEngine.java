package dev.pti.etl.rules;

import dev.pti.common.error.RuleViolationException;
import dev.pti.etl.config.DqProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Runs the per-record rules of one kind of record in id order and stops at the first violation (DOC-16 §2.4), so
 * a record produces exactly one dead letter.
 */
public final class RuleEngine<T> {

    private final List<RecordRule<T>> rules;
    private final DqProperties properties;

    public RuleEngine(List<? extends RecordRule<T>> rules, DqProperties properties) {
        List<RecordRule<T>> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(RecordRule::id));
        this.rules = List.copyOf(sorted);
        this.properties = properties;
    }

    /** @throws RuleViolationException for the first rule the record breaks */
    public void check(T record, RuleContext context) {
        for (RecordRule<T> rule : rules) {
            if (!properties.enabled(rule.id()) || (context.replay() && !rule.appliesDuringReplay())) {
                continue;
            }
            Optional<String> violation = rule.check(record, context);
            if (violation.isPresent()) {
                throw new RuleViolationException(rule.stage(), rule.id(), violation.get());
            }
        }
    }

    public List<String> ruleIds() {
        return rules.stream().map(RecordRule::id).toList();
    }
}
