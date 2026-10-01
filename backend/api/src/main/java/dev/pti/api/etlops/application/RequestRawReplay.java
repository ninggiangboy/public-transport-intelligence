package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.ReplayRequestStore;
import dev.pti.api.etlops.domain.ActiveReplayConflict;
import dev.pti.api.etlops.domain.IdempotencyKeyReusedException;
import dev.pti.api.etlops.domain.RawReplayRules;
import dev.pti.api.etlops.domain.ReplayAlreadyRunningException;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.etlops.domain.ReplayWindowInvalidException;
import dev.pti.api.etlops.domain.UnsupportedSourceException;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /etl/replays} (DOC-32 E-50, DOC-22 §4.1): asks {@code etl-batch} to replay a range of the raw zone. The
 * API only writes the {@code RAW_RANGE} row of {@code ops.replay_request}; the {@code ReplayRequestPoller} starts
 * {@code RawZoneReplayJob} (ADR-0013). A second request for a source that has one waiting or running is a 409 with
 * the id of that one (FR-12.3), which the table itself guarantees, so two requests at once cannot both get in.
 */
public final class RequestRawReplay {

    private static final Logger log = LoggerFactory.getLogger(RequestRawReplay.class);

    private final ReplayRequestStore store;
    private final TransactionRunner operatorTx;
    private final RawReplayRules rules;
    private final WriteMetrics metrics;
    private final BusinessClock clock;
    private final Supplier<UUID> ids;

    public RequestRawReplay(
            ReplayRequestStore store,
            TransactionRunner operatorTx,
            RawReplayRules rules,
            WriteMetrics metrics,
            BusinessClock clock,
            Supplier<UUID> ids) {
        this.store = store;
        this.operatorTx = operatorTx;
        this.rules = rules;
        this.metrics = metrics;
        this.clock = clock;
        this.ids = ids;
    }

    /**
     * @param source the {@code ops.etl_source} name as the client sent it
     * @param fromTs inclusive, on the Kafka record time
     * @param toTs exclusive
     * @throws ValidationException when the source is not one of the sources
     * @throws UnsupportedSourceException for {@code GTFS_STATIC}
     * @throws ReplayWindowInvalidException when the window breaks the rules of DOC-22 §4.1
     * @throws ReplayAlreadyRunningException when the source has a replay waiting or running
     */
    public Submitted<ReplayRequest> execute(
            Caller caller,
            String source,
            Instant fromTs,
            Instant toTs,
            boolean recomputeAnalytics,
            @Nullable String idempotencyKey) {
        return Measured.write(metrics, "replay_raw", Measured::of, () -> {
            checkSource(source);
            Instant now = clock.realNow();
            List<FieldError> problems = rules.problems(fromTs, toTs, now);
            if (!problems.isEmpty()) {
                throw new ReplayWindowInvalidException(problems);
            }
            // The table keeps microseconds: compare and store what it will hold.
            ReplayRequest draft = ReplayRequest.pendingRange(
                    ids.get(),
                    source,
                    fromTs.truncatedTo(ChronoUnit.MICROS),
                    toTs.truncatedTo(ChronoUnit.MICROS),
                    recomputeAnalytics,
                    caller.actor(),
                    idempotencyKey,
                    now);
            Submitted<ReplayRequest> submitted;
            try {
                submitted = operatorTx.inTransaction(() -> submit(draft));
            } catch (ActiveReplayConflict e) {
                UUID existing = operatorTx
                        .inTransaction(() -> store.activeRawReplay(source))
                        .orElse(null);
                throw new ReplayAlreadyRunningException(
                        "A replay of " + source + " is already waiting or running.", existing);
            }
            if (!submitted.idempotent()) {
                log.atInfo()
                        .addKeyValue("replayRequestId", submitted.value().id())
                        .addKeyValue("source", source)
                        .addKeyValue("actor", draft.requestedBy())
                        .log("replay requested");
            }
            return submitted;
        });
    }

    private static void checkSource(String source) {
        if (!RawReplayRules.isKnownSource(source)) {
            throw ValidationException.of("source", "must be one of " + String.join(", ", RawReplayRules.ALL_SOURCES));
        }
        if (!RawReplayRules.SOURCES.contains(source)) {
            throw new UnsupportedSourceException(
                    source + " has no raw zone to replay from; load the feed again with GtfsStaticLoadJob.");
        }
    }

    private Submitted<ReplayRequest> submit(ReplayRequest draft) {
        if (draft.idempotencyKey() != null) {
            Optional<ReplayRequest> prior = store.findByKey(draft.requestedBy(), draft.idempotencyKey());
            if (prior.isPresent()) {
                return answer(prior.get(), draft);
            }
        }
        Optional<ReplayRequest> inserted = store.insert(draft);
        if (inserted.isPresent()) {
            return Submitted.created(inserted.get());
        }
        // Another request with the same key got in between: it is the one that counts.
        ReplayRequest winner = store.findByKey(draft.requestedBy(), draft.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("A replay request vanished after a key conflict"));
        return answer(winner, draft);
    }

    private static Submitted<ReplayRequest> answer(ReplayRequest prior, ReplayRequest draft) {
        if (!prior.sameAsk(draft)) {
            throw new IdempotencyKeyReusedException();
        }
        return Submitted.repeated(prior);
    }
}
