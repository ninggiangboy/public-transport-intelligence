package dev.pti.analytics.eta.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.eta.application.EtaPlan.Status;
import dev.pti.analytics.eta.application.EtaRouteRun.Completion;
import dev.pti.analytics.support.AnalyticsFakes;
import dev.pti.analytics.support.AnalyticsFakes.Journal;
import dev.pti.analytics.support.AnalyticsFakes.Limits;
import dev.pti.analytics.support.AnalyticsFakes.Lock;
import dev.pti.analytics.support.AnalyticsFakes.Metrics;
import dev.pti.analytics.support.AnalyticsFakes.Reference;
import dev.pti.analytics.support.AnalyticsFakes.Transactions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The planner and the per-route aggregator of DOC-23 §7.2 with in-memory ports (AN-E-08, AN-E-11 and the lock). */
class EtaAggregationTest {

    private static final Instant HOUR = Instant.parse("2026-09-29T21:00:00Z");

    private final Journal journal = new Journal();
    private final EtaFakes.Store store = new EtaFakes.Store(journal);
    private final Reference reference = new Reference();
    private final Lock lock = new Lock(journal);
    private final Limits limits = new Limits(journal);
    private final Transactions tx = new Transactions();
    private final Metrics metrics = new Metrics();
    private final EtaRunPlanner planner =
            new EtaRunPlanner(store, reference, AnalyticsFakes.clockAt("2026-09-29T21:05:00Z"));
    private final EtaAggregator aggregator = new EtaAggregator(
            store,
            reference,
            lock,
            limits,
            tx,
            new RunReporter(metrics),
            AnalyticsPropertiesFixtures.defaults().eta().toSettings());

    @Test
    void theFirstRunPlansEveryRouteWithTheFingerprintOfTheData() {
        EtaPlan plan = planner.plan(new EtaPlanRequest(HOUR, false));

        assertThat(plan.status()).isEqualTo(Status.RUN);
        assertThat(plan.routeIds()).containsExactly("18", "901");
        assertThat(plan.watermark()).isEqualTo("42|2026-09-29 20:59:00+00");
        assertThat(store.watermarkRange.from())
                .as("the day before the hour's day")
                .isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(store.watermarkRange.to()).isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    void anE08AnUnchangedWatermarkPlansNothing() {
        store.checkpoint = store.watermark;

        EtaPlan plan = planner.plan(new EtaPlanRequest(HOUR, false));

        assertThat(plan.status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(plan.routeIds()).isEmpty();
    }

    @Test
    void aForcedRunIgnoresTheCheckpoint() {
        store.checkpoint = store.watermark;

        EtaPlan plan = planner.plan(new EtaPlanRequest(HOUR, true));

        assertThat(plan.status()).isEqualTo(Status.RUN);
    }

    @Test
    void aChangedWatermarkPlansTheRun() {
        store.checkpoint = "41|2026-09-29 20:58:00+00";

        assertThat(planner.plan(new EtaPlanRequest(HOUR, false)).status()).isEqualTo(Status.RUN);
    }

    @Test
    void withoutAnActiveFeedThePlanIsEmptyAndNothingIsRead() {
        reference.active = false;

        EtaPlan plan = planner.plan(new EtaPlanRequest(HOUR, true));

        assertThat(plan.status()).isEqualTo(Status.NO_FEED);
        assertThat(store.watermarkRange).isNull();
    }

    @Test
    void aRouteTakesTheTableLockAfterTheStatementTimeoutAndBeforeTheMerge() {
        UUID batch = UUID.randomUUID();

        RunResult result = aggregator.aggregate(new EtaRouteRun("18", HOUR, batch, Trigger.JOB, null));

        assertThat(journal.entries).containsExactly("statement_timeout 60s", "lock pti:analytics:eta", "recompute 18");
        assertThat(lock.lockTimeout).isEqualTo(Duration.ofSeconds(60));
        assertThat(tx.joined)
                .as("the caller's transaction is joined, or one is started")
                .isEqualTo(1);
        assertThat(tx.started).isZero();
        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.updated()).as("rows upserted").isEqualTo(3);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.scope()).isEqualTo("18");
        assertThat(result.batchId()).isEqualTo(batch);
        assertThat(store.batches).containsExactly(batch);
    }

    @Test
    void theWindowIsTwentyEightDaysBeforeTheHourAndComputedAtIsTheHour() {
        aggregator.aggregate(new EtaRouteRun("18", HOUR, UUID.randomUUID(), Trigger.JOB, null));

        assertThat(store.windows.getFirst().from()).isEqualTo(Instant.parse("2026-09-01T21:00:00Z"));
        assertThat(store.windows.getFirst().to()).isEqualTo(HOUR);
        assertThat(store.computedAt).containsExactly(HOUR);
    }

    @Test
    void onlyTheLastRouteStoresTheCheckpointAndInTheSameTransaction() {
        UUID batch = UUID.randomUUID();
        aggregator.aggregate(new EtaRouteRun("18", HOUR, batch, Trigger.JOB, null));
        aggregator.aggregate(new EtaRouteRun("901", HOUR, batch, Trigger.JOB, new Completion("42|x", 77L)));

        assertThat(journal.entries)
                .containsSubsequence("recompute 18", "recompute 901", "checkpoint")
                .filteredOn("checkpoint"::equals)
                .hasSize(1);
        assertThat(store.savedCheckpoints).containsExactly("42|x@" + HOUR);
        assertThat(store.savedExecution).isEqualTo(77L);
        assertThat(tx.joined).isEqualTo(2);
    }

    @Test
    void theRunIsCountedAndTimedAsAJobRun() {
        aggregator.aggregate(new EtaRouteRun("18", HOUR, UUID.randomUUID(), Trigger.JOB, null));
        aggregator.aggregate(new EtaRouteRun("901", HOUR, UUID.randomUUID(), Trigger.RECOMPUTE, null));

        assertThat(metrics.runs).containsExactly("eta/job/ok", "eta/recompute/ok");
        assertThat(metrics.durations).hasSize(2);
    }

    @Test
    void withoutAnActiveFeedARouteIsANoopThatTouchesNothing() {
        reference.active = false;

        RunResult result = aggregator.aggregate(
                new EtaRouteRun("18", HOUR, UUID.randomUUID(), Trigger.JOB, new Completion("x", 1L)));

        assertThat(result.outcome()).isEqualTo(Outcome.NOOP);
        assertThat(journal.entries).isEmpty();
        assertThat(metrics.runs).containsExactly("eta/job/noop");
        assertThat(metrics.durations).as("only a unit that had work is timed").isEmpty();
    }

    @Test
    void aFailureIsCountedAndRaisedSoThatTheStepFailsAndRestarts() {
        store.failure = new IllegalStateException("statement timeout");

        assertThatThrownBy(() -> aggregator.aggregate(
                        new EtaRouteRun("18", HOUR, UUID.randomUUID(), Trigger.JOB, new Completion("x", 1L))))
                .isSameAs(store.failure);

        assertThat(metrics.runs).containsExactly("eta/job/error");
        assertThat(journal.entries).doesNotContain("checkpoint");
    }
}
