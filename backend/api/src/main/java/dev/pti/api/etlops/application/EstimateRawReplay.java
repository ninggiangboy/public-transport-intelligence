package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.ReplayReader;
import dev.pti.api.etlops.domain.RawReplayRules;
import dev.pti.api.etlops.domain.ReplayEstimate;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /etl/replays/estimate} (DOC-32 E-53): how many messages and how long a raw zone replay of a window will
 * take, from the log of micro-batches (the API cannot list the raw zone, TB-2). The window is checked by the rules of
 * E-50, but a problem is a 400 here, because this is a GET. An operator can still replay a window that has no history.
 */
public final class EstimateRawReplay {

    private final ReplayReader replays;
    private final RawReplayRules rules;
    private final BusinessClock clock;
    private final int throughput;
    private final TransactionRunner tx;

    /** @param throughput messages per second a replay does ({@code pti.api.replay-estimate.throughput}) */
    public EstimateRawReplay(
            ReplayReader replays, RawReplayRules rules, BusinessClock clock, int throughput, TransactionRunner tx) {
        this.replays = replays;
        this.rules = rules;
        this.clock = clock;
        this.throughput = throughput;
        this.tx = tx;
    }

    public ReplayEstimate execute(String source, Instant fromTs, Instant toTs) {
        List<FieldError> errors = new ArrayList<>();
        if (!RawReplayRules.SOURCES.contains(source)) {
            errors.add(new FieldError(
                    "source",
                    "must be one of "
                            + String.join(
                                    ", ",
                                    RawReplayRules.SOURCES.stream().sorted().toList())));
        } else {
            errors.addAll(rules.problems(fromTs, toTs, clock.realNow()));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("The replay window is not valid.", errors);
        }
        return tx.inTransaction(() -> ReplayEstimate.of(
                source,
                fromTs,
                toTs,
                replays.history(source, fromTs, toTs),
                throughput,
                replays.rawReplayActive(source)));
    }
}
