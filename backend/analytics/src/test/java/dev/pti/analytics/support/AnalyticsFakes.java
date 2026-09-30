package dev.pti.analytics.support;

import dev.pti.analytics.core.application.port.AdvisoryLock;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.DayType;
import dev.pti.common.tx.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

/** In-memory stand-ins for the ports that the job use cases share: lock, limits, transactions, metrics, feed. */
public final class AnalyticsFakes {

    private AnalyticsFakes() {}

    /** The order in which the use case touched its collaborators, to assert "lock before statement". */
    public static final class Journal {

        public final List<String> entries = new ArrayList<>();

        public void add(String entry) {
            entries.add(entry);
        }
    }

    /** A business clock fixed at an instant. */
    public static BusinessClock clockAt(String instant) {
        return new BusinessClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), Duration.ZERO);
    }

    /** A feed that is ACTIVE or not; its zone is {@code America/Chicago}. */
    public static final class Reference implements AnalyticsReferenceCache {

        public boolean active = true;

        @Override
        public boolean hasActiveFeed() {
            return active;
        }

        @Override
        public long feedVersionId() {
            return 1;
        }

        @Override
        public ZoneId agencyZone() {
            return ZoneId.of("America/Chicago");
        }

        @Override
        public Optional<RouteInfo> route(String routeId) {
            return Optional.empty();
        }

        @Override
        public Optional<TripPattern> trip(String tripId) {
            return Optional.empty();
        }

        @Override
        public OptionalInt scheduledHeadway(String routeId, int directionId, DayType dayType, int hourOfServiceDay) {
            return OptionalInt.empty();
        }

        @Override
        public DayType dayType(LocalDate serviceDate) {
            return DayType.of(serviceDate);
        }
    }

    public static final class Lock implements AdvisoryLock {

        private final Journal journal;
        public final List<String> names = new ArrayList<>();
        public Duration lockTimeout;

        public Lock(Journal journal) {
            this.journal = journal;
        }

        @Override
        public boolean tryAcquire(String lockName) {
            throw new AssertionError("A job waits for its lock: " + lockName);
        }

        @Override
        public void acquire(String lockName, Duration timeout) {
            names.add(lockName);
            lockTimeout = timeout;
            journal.add("lock " + lockName);
        }
    }

    public static final class Limits implements TransactionLimits {

        private final Journal journal;
        public Duration statementTimeout;

        public Limits(Journal journal) {
            this.journal = journal;
        }

        @Override
        public void statementTimeout(Duration timeout) {
            statementTimeout = timeout;
            journal.add("statement_timeout " + timeout.toSeconds() + "s");
        }
    }

    /** Runs the work at once and counts how it was asked for a transaction. */
    public static final class Transactions implements TransactionRunner {

        public int joined;
        public int started;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            joined++;
            return work.get();
        }

        @Override
        public <T> T inNewTransaction(Supplier<T> work) {
            started++;
            return work.get();
        }
    }

    public static final class Metrics implements AnalyticsMetrics {

        public final List<String> runs = new ArrayList<>();
        public final List<Detector> durations = new ArrayList<>();

        @Override
        public void run(Detector detector, Trigger trigger, Outcome outcome) {
            runs.add(detector.tag() + "/" + trigger.tag() + "/" + outcome.tag());
        }

        @Override
        public void runDuration(Detector detector, Duration duration) {
            durations.add(detector);
        }

        @Override
        public void dispatchDelay(Duration delay) {}

        @Override
        public void lateBatch(Detector detector) {}

        @Override
        public void skippedTicks(Detector detector, long count) {}

        @Override
        public void openEpisodes(Detector detector, long count) {}

        @Override
        public void dropped() {}
    }
}
