package dev.pti.api.system.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.system.adapter.out.cache.ProbeDataAsOfReader;
import dev.pti.api.system.adapter.out.metrics.MicrometerFreshnessMetrics;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.SourceKind;
import dev.pti.testing.TestClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FreshnessAdaptersTest {

    private final TestClock clock = TestClock.atDefault();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerFreshnessMetrics metrics = new MicrometerFreshnessMetrics(registry, clock);

    private FreshnessSnapshot snapshot(Map<SourceKind, Instant> events) {
        return new FreshnessSnapshot(clock.instant(), null, events, null, null, null, false);
    }

    private Gauge gauge(SourceKind source) {
        return registry.find("pti.source.last.event.age")
                .tag("source", source.name())
                .gauge();
    }

    @Test
    @DisplayName("O-06 the gauge is the business age of each source and keeps growing between probes")
    void gaugeGrowsWithTime() {
        metrics.publish(snapshot(Map.of(
                SourceKind.GTFS_RT_VEHICLE_POSITION, clock.instant().minusSeconds(5),
                SourceKind.TICKETING_SALES, clock.instant().minusSeconds(44))));

        assertThat(gauge(SourceKind.GTFS_RT_VEHICLE_POSITION).value()).isEqualTo(5.0);
        assertThat(gauge(SourceKind.TICKETING_SALES).value()).isEqualTo(44.0);

        clock.advance(Duration.ofSeconds(120));

        assertThat(gauge(SourceKind.GTFS_RT_VEHICLE_POSITION).value()).isEqualTo(125.0);
    }

    @Test
    @DisplayName("A source that never had data has no series")
    void noSeriesWithoutData() {
        metrics.publish(snapshot(Map.of(SourceKind.GTFS_RT_VEHICLE_POSITION, clock.instant())));

        assertThat(gauge(SourceKind.GTFS_RT_VEHICLE_POSITION)).isNotNull();
        assertThat(gauge(SourceKind.GTFS_RT_TRIP_UPDATE)).isNull();
        assertThat(gauge(SourceKind.TICKETING_SALES)).isNull();
    }

    @Test
    @DisplayName("When the database cannot be read the series are gone, and return with the next good probe")
    void clearRemovesTheSeries() {
        metrics.publish(snapshot(Map.of(SourceKind.GTFS_RT_TRIP_UPDATE, clock.instant())));
        metrics.clear();

        assertThat(gauge(SourceKind.GTFS_RT_TRIP_UPDATE)).isNull();

        metrics.publish(
                snapshot(Map.of(SourceKind.GTFS_RT_TRIP_UPDATE, clock.instant().minusSeconds(2))));

        assertThat(gauge(SourceKind.GTFS_RT_TRIP_UPDATE).value()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("DOC-31 §7.3 X-Data-As-Of comes from the last probe: sources, ETA and OTP")
    void dataAsOfReadsTheProbeResult() {
        Instant now = clock.instant();
        AtomicReference<FreshnessSnapshot> current = new AtomicReference<>();
        FreshnessSnapshots snapshots = new FreshnessSnapshots() {
            @Override
            public Optional<FreshnessSnapshot> current() {
                return Optional.ofNullable(current.get());
            }

            @Override
            public void store(FreshnessSnapshot snapshot) {
                current.set(snapshot);
            }
        };
        ProbeDataAsOfReader reader = new ProbeDataAsOfReader(snapshots);

        assertThat(reader.asOf(AsOfKind.VEHICLE_POSITION)).isEmpty();

        snapshots.store(new FreshnessSnapshot(
                now,
                null,
                Map.of(
                        SourceKind.GTFS_RT_VEHICLE_POSITION, now.minusSeconds(1),
                        SourceKind.GTFS_RT_TRIP_UPDATE, now.minusSeconds(2),
                        SourceKind.TICKETING_SALES, now.minusSeconds(3)),
                now.minusSeconds(4),
                now,
                now.minusSeconds(5),
                false));

        assertThat(reader.asOf(AsOfKind.VEHICLE_POSITION)).contains(now.minusSeconds(1));
        assertThat(reader.asOf(AsOfKind.TRIP_UPDATE)).contains(now.minusSeconds(2));
        assertThat(reader.asOf(AsOfKind.TICKET_SALES)).contains(now.minusSeconds(3));
        assertThat(reader.asOf(AsOfKind.ETA_PREDICTION)).contains(now.minusSeconds(4));
        assertThat(reader.asOf(AsOfKind.OTP_SCORECARD)).contains(now.minusSeconds(5));
    }

    @Test
    void dataAsOfOfASourceWithoutDataIsAbsent() {
        Instant now = clock.instant();
        FreshnessSnapshot snapshot = new FreshnessSnapshot(now, null, Map.of(), null, null, null, false);
        ProbeDataAsOfReader reader = new ProbeDataAsOfReader(new FreshnessSnapshots() {
            @Override
            public Optional<FreshnessSnapshot> current() {
                return Optional.of(snapshot);
            }

            @Override
            public void store(FreshnessSnapshot ignored) {}
        });

        for (AsOfKind kind : AsOfKind.values()) {
            assertThat(reader.asOf(kind)).as(kind.name()).isEmpty();
        }
    }
}
