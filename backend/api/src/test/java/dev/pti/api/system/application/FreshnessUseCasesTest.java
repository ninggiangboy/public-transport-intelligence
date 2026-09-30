package dev.pti.api.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.ServiceUnavailableException;
import dev.pti.api.system.application.port.FreshnessMetrics;
import dev.pti.api.system.application.port.FreshnessQuery;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.Freshness;
import dev.pti.api.system.domain.Freshness.SourceStatus;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.FreshnessThresholds;
import dev.pti.api.system.domain.SourceKind;
import dev.pti.api.system.domain.SourceReading;
import dev.pti.common.time.BusinessClock;
import dev.pti.testing.TestClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The freshness probe and the endpoint built on it (DOC-32 E-60, EP-33). */
class FreshnessUseCasesTest {

    private static final ActiveFeed FEED = new ActiveFeed(
            3,
            "2026-08-23",
            ZoneId.of("America/Chicago"),
            LocalDate.parse("2026-08-23"),
            LocalDate.parse("2026-12-12"),
            Instant.parse("2026-09-27T08:34:40Z"));

    private final TestClock clock = TestClock.atDefault();
    private final FakeQuery query = new FakeQuery();
    private final MemorySnapshots snapshots = new MemorySnapshots();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final RefreshFreshness refresh =
            new RefreshFreshness(query, snapshots, metrics, () -> Optional.of(FEED), clock, Duration.ofMinutes(5));
    private final GetFreshness get = new GetFreshness(
            snapshots,
            clock,
            new FreshnessThresholds(Duration.ofSeconds(120), Duration.ofSeconds(900)),
            Duration.ofSeconds(60));

    private static Instant ago(Instant base, long seconds) {
        return base.minusSeconds(seconds);
    }

    // -------------------------------------------------------------------------------------------- probe

    @Test
    @DisplayName("A probe keeps what it read, the ACTIVE feed and the time, and feeds the gauge")
    void probeStoresAndPublishes() {
        Instant now = clock.instant();
        query.reading = new SourceReading(ago(now, 5), ago(now, 33), ago(now, 44), ago(now, 3600));
        query.eta = Optional.of(ago(now, 900));

        refresh.execute();

        FreshnessSnapshot snapshot = snapshots.current().orElseThrow();
        assertThat(snapshot.checkedAt()).isEqualTo(now);
        assertThat(snapshot.activeFeed()).isEqualTo(FEED);
        assertThat(snapshot.lastEventAt())
                .containsEntry(SourceKind.GTFS_RT_VEHICLE_POSITION, ago(now, 5))
                .containsEntry(SourceKind.GTFS_RT_TRIP_UPDATE, ago(now, 33))
                .containsEntry(SourceKind.TICKETING_SALES, ago(now, 44));
        assertThat(snapshot.etaComputedAt()).isEqualTo(ago(now, 900));
        assertThat(snapshot.otpComputedAt()).isEqualTo(ago(now, 3600));
        assertThat(snapshot.probeError()).isFalse();
        assertThat(metrics.published).containsExactly(snapshot);
    }

    @Test
    @DisplayName("A source that never had data has no entry")
    void missingSourceHasNoEntry() {
        query.reading = new SourceReading(clock.instant(), null, null, null);

        refresh.execute();

        assertThat(snapshots.current().orElseThrow().lastEventAt())
                .containsOnlyKeys(SourceKind.GTFS_RT_VEHICLE_POSITION);
    }

    @Test
    @DisplayName("The ETA table is read once, then again only after the insight interval (5 minutes)")
    void etaIsReadEveryFiveMinutes() {
        query.reading = new SourceReading(clock.instant(), null, null, null);
        query.eta = Optional.of(Instant.parse("2026-09-29T21:05:12Z"));

        refresh.execute();
        clock.advance(Duration.ofSeconds(15));
        refresh.execute();
        clock.advance(Duration.ofSeconds(15));
        refresh.execute();

        assertThat(query.etaReads).hasValue(1);
        assertThat(snapshots.current().orElseThrow().etaComputedAt()).isEqualTo(Instant.parse("2026-09-29T21:05:12Z"));

        clock.advance(Duration.ofMinutes(5));
        query.eta = Optional.of(Instant.parse("2026-09-29T21:25:00Z"));
        refresh.execute();

        assertThat(query.etaReads).hasValue(2);
        assertThat(snapshots.current().orElseThrow().etaComputedAt()).isEqualTo(Instant.parse("2026-09-29T21:25:00Z"));
    }

    @Test
    @DisplayName("A failed probe never throws: it keeps the old result with probeError and removes the gauge")
    void failedProbeKeepsTheOldResult() {
        query.reading = new SourceReading(clock.instant(), null, null, null);
        refresh.execute();
        FreshnessSnapshot good = snapshots.current().orElseThrow();
        clock.advance(Duration.ofSeconds(15));
        query.failure = new IllegalStateException("database is down");

        refresh.execute();

        FreshnessSnapshot kept = snapshots.current().orElseThrow();
        assertThat(kept.probeError()).isTrue();
        assertThat(kept.checkedAt()).isEqualTo(good.checkedAt());
        assertThat(kept.lastEventAt()).isEqualTo(good.lastEventAt());
        assertThat(metrics.cleared).isEqualTo(1);
    }

    @Test
    void aFailedFirstProbeLeavesNothing() {
        query.failure = new IllegalStateException("database is down");

        refresh.execute();

        assertThat(snapshots.current()).isEmpty();
        assertThat(metrics.cleared).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ endpoint

    @Test
    @DisplayName("Age is businessNow - lastEventAt, rounded down; stale is age > staleAfter")
    void computesAgesAtRequestTime() {
        Instant probed = clock.instant();
        query.reading = new SourceReading(ago(probed, 5), ago(probed, 33), ago(probed, 44), ago(probed, 100));
        refresh.execute();
        clock.advance(Duration.ofSeconds(5));

        Freshness freshness = get.execute();

        assertThat(freshness.businessNow()).isEqualTo(probed.plusSeconds(5));
        assertThat(freshness.clockOffset()).isEqualTo(Duration.ZERO);
        assertThat(freshness.checkedAt()).isEqualTo(probed);
        assertThat(freshness.stale()).isFalse();
        assertThat(freshness.activeFeed()).isEqualTo(FEED);
        assertThat(freshness.sources())
                .containsExactly(
                        new SourceStatus(SourceKind.GTFS_RT_VEHICLE_POSITION, ago(probed, 5), 10L, 120, false),
                        new SourceStatus(SourceKind.GTFS_RT_TRIP_UPDATE, ago(probed, 33), 38L, 120, false),
                        new SourceStatus(SourceKind.TICKETING_SALES, ago(probed, 44), 49L, 900, false));
    }

    @Test
    @DisplayName("EP-33 a GTFS-realtime source older than 120 s is stale and so is the system")
    void gtfsRealtimeStalenessRaisesTheBanner() {
        Instant probed = clock.instant();
        query.reading = new SourceReading(ago(probed, 121), ago(probed, 5), ago(probed, 5), null);
        refresh.execute();

        Freshness freshness = get.execute();

        assertThat(freshness.stale()).isTrue();
        assertThat(freshness.sources().get(0).stale()).isTrue();
        assertThat(freshness.sources().get(1).stale()).isFalse();
    }

    @Test
    void exactlyAtTheThresholdIsNotStale() {
        Instant probed = clock.instant();
        query.reading = new SourceReading(ago(probed, 120), ago(probed, 120), ago(probed, 900), null);
        refresh.execute();

        assertThat(get.execute().stale()).isFalse();
    }

    @Test
    @DisplayName("Ticketing stale alone does not raise the banner")
    void ticketingAloneIsNotTheBanner() {
        Instant probed = clock.instant();
        query.reading = new SourceReading(ago(probed, 5), ago(probed, 5), ago(probed, 901), null);
        refresh.execute();

        Freshness freshness = get.execute();

        assertThat(freshness.stale()).isFalse();
        assertThat(freshness.sources().get(2).stale()).isTrue();
    }

    @Test
    @DisplayName("A source without data has no lastEventAt or age, is stale, and raises the banner if it is GTFS-rt")
    void sourceWithoutData() {
        query.reading = new SourceReading(null, clock.instant(), null, null);
        refresh.execute();

        Freshness freshness = get.execute();

        SourceStatus vehicles = freshness.sources().get(0);
        assertThat(vehicles.lastEventAt()).isNull();
        assertThat(vehicles.ageSeconds()).isNull();
        assertThat(vehicles.stale()).isTrue();
        assertThat(freshness.stale()).isTrue();
    }

    @Test
    @DisplayName("With a clock offset of -12 h, ages are in business time and the offset is reported")
    void ageFollowsTheBusinessClock() {
        BusinessClock shifted = new BusinessClock(
                Clock.fixed(Instant.parse("2026-09-29T21:20:00Z"), ZoneOffset.UTC), Duration.ofHours(-12));
        MemorySnapshots store = new MemorySnapshots();
        query.reading = new SourceReading(shifted.instant().minusSeconds(40), null, null, null);
        new RefreshFreshness(query, store, metrics, Optional::empty, shifted, Duration.ofMinutes(5)).execute();

        Freshness freshness = new GetFreshness(
                        store,
                        shifted,
                        new FreshnessThresholds(Duration.ofSeconds(120), Duration.ofSeconds(900)),
                        Duration.ofSeconds(60))
                .execute();

        assertThat(freshness.businessNow()).isEqualTo(Instant.parse("2026-09-29T09:20:00Z"));
        assertThat(freshness.clockOffset()).isEqualTo(Duration.ofHours(-12));
        assertThat(freshness.sources().get(0).ageSeconds()).isEqualTo(40L);
        assertThat(freshness.activeFeed()).isNull();
    }

    @Test
    @DisplayName("probeError is reported with the old figures")
    void reportsProbeError() {
        query.reading = new SourceReading(clock.instant(), clock.instant(), clock.instant(), null);
        refresh.execute();
        query.failure = new IllegalStateException("down");
        refresh.execute();

        assertThat(get.execute().probeError()).isTrue();
    }

    @Test
    @DisplayName("A result older than 60 s, or none, is a 503 that says to retry in 5 seconds")
    void oldOrMissingResultIs503() {
        assertThatThrownBy(get::execute).isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
            assertThat(e.retryAfterSeconds()).isEqualTo(5);
        });

        query.reading = new SourceReading(clock.instant(), null, null, null);
        refresh.execute();
        clock.advance(Duration.ofSeconds(60));
        assertThat(get.execute()).isNotNull();
        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(get::execute).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void currentUserUseCase() {
        GetCurrentUser useCase = new GetCurrentUser();

        assertThat(useCase.execute(dev.pti.api.platform.domain.Caller.anonymous()))
                .isEqualTo(dev.pti.api.system.domain.CurrentUser.anonymous());
        var operator = useCase.execute(new dev.pti.api.platform.domain.Caller(
                "operator",
                "Demo Operator",
                java.util.EnumSet.allOf(dev.pti.api.platform.domain.Role.class),
                Instant.parse("2026-09-29T21:24:35Z")));
        assertThat(operator.authenticated()).isTrue();
        assertThat(operator.roles()).containsExactly("operator", "viewer");
        assertThat(operator.tokenExpiresAt()).isEqualTo(Instant.parse("2026-09-29T21:24:35Z"));
    }

    // -------------------------------------------------------------------------------------------- fakes

    private static final class FakeQuery implements FreshnessQuery {

        SourceReading reading = new SourceReading(null, null, null, null);
        Optional<Instant> eta = Optional.empty();
        RuntimeException failure;
        final AtomicInteger etaReads = new AtomicInteger();

        @Override
        public SourceReading readSources() {
            if (failure != null) {
                throw failure;
            }
            return reading;
        }

        @Override
        public Optional<Instant> readEtaComputedAt() {
            etaReads.incrementAndGet();
            return eta;
        }
    }

    private static final class MemorySnapshots implements FreshnessSnapshots {

        private final AtomicReference<FreshnessSnapshot> current = new AtomicReference<>();

        @Override
        public Optional<FreshnessSnapshot> current() {
            return Optional.ofNullable(current.get());
        }

        @Override
        public void store(FreshnessSnapshot snapshot) {
            current.set(snapshot);
        }
    }

    private static final class RecordingMetrics implements FreshnessMetrics {

        final List<FreshnessSnapshot> published = new ArrayList<>();
        int cleared;

        @Override
        public void publish(FreshnessSnapshot snapshot) {
            published.add(snapshot);
        }

        @Override
        public void clear() {
            cleared++;
        }
    }
}
