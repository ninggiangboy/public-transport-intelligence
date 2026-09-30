package dev.pti.analytics.eta.application;

import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.eta.application.port.EtaAggregateStore;
import dev.pti.analytics.eta.domain.EtaWindow;
import dev.pti.analytics.support.AnalyticsFakes.Journal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** In-memory stand-ins for the ports of the ETA aggregation. */
final class EtaFakes {

    private EtaFakes() {}

    /** The store: a fixed route list and watermark, a checkpoint, and a record of the merges it was asked for. */
    static final class Store implements EtaAggregateStore {

        final Journal journal;
        List<String> routes = List.of("18", "901");
        String watermark = "42|2026-09-29 20:59:00+00";

        @Nullable
        String checkpoint;

        Merge merge = new Merge(3, 1);
        RuntimeException failure;

        final List<String> recomputed = new ArrayList<>();
        final List<EtaWindow> windows = new ArrayList<>();
        final List<Instant> computedAt = new ArrayList<>();
        final List<UUID> batches = new ArrayList<>();
        final List<String> savedCheckpoints = new ArrayList<>();

        @Nullable
        Long savedExecution;

        DateRange watermarkRange;

        Store(Journal journal) {
            this.journal = journal;
        }

        @Override
        public List<String> routeIds() {
            return routes;
        }

        @Override
        public String sourceWatermark(DateRange serviceDates) {
            watermarkRange = serviceDates;
            return watermark;
        }

        @Override
        public Optional<String> checkpoint() {
            return Optional.ofNullable(checkpoint);
        }

        @Override
        public void saveCheckpoint(String watermark, Instant watermarkTs, @Nullable Long jobExecutionId) {
            journal.add("checkpoint");
            savedCheckpoints.add(watermark + "@" + watermarkTs);
            savedExecution = jobExecutionId;
        }

        @Override
        public Merge recompute(String routeId, EtaWindow window, Instant computedAt, UUID batchId) {
            journal.add("recompute " + routeId);
            if (failure != null) {
                throw failure;
            }
            recomputed.add(routeId);
            windows.add(window);
            this.computedAt.add(computedAt);
            batches.add(batchId);
            return merge;
        }
    }
}
