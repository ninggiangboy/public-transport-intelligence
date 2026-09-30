package dev.pti.analytics.disruption.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The sliding window of DOC-23 §6.1: {@code [end − 10 min, end)}, per direction, mean and largest delay per stop. */
class ArrivalSeriesTest {

    private static final Instant END = Instant.parse("2026-09-29T12:10:00Z");

    private final DisruptionThresholds thresholds =
            AnalyticsPropertiesFixtures.defaults().disruption().toThresholds();

    private static Arrival arrival(int direction, String stop, String at, int delay) {
        return new Arrival(direction, stop, Instant.parse(at), delay);
    }

    @Test
    void theWindowIncludesItsStartAndExcludesItsEnd() {
        ArrivalSeries series = new ArrivalSeries(
                List.of(
                        arrival(0, "A", "2026-09-29T11:59:59Z", 1000),
                        arrival(0, "A", "2026-09-29T12:00:00Z", 60),
                        arrival(0, "B", "2026-09-29T12:05:00Z", 120),
                        arrival(0, "B", "2026-09-29T12:09:59Z", 180),
                        arrival(0, "C", "2026-09-29T12:10:00Z", 1000)),
                thresholds);

        BucketObservation observation = series.observe(0, END);

        assertThat(observation.sampleCount()).isEqualTo(3);
        assertThat(observation.meanDelay()).isCloseTo(120.0, within(1e-9));
    }

    @Test
    void directionsAreSeparate() {
        ArrivalSeries series = new ArrivalSeries(
                List.of(arrival(0, "A", "2026-09-29T12:05:00Z", 60), arrival(1, "A", "2026-09-29T12:05:00Z", 600)),
                thresholds);

        assertThat(series.observe(0, END).meanDelay()).isEqualTo(60);
        assertThat(series.observe(1, END).meanDelay()).isEqualTo(600);
    }

    @Test
    void noArrivalsGiveAnEmptyBucket() {
        ArrivalSeries series = new ArrivalSeries(List.of(arrival(0, "A", "2026-09-29T13:00:00Z", 60)), thresholds);

        assertThat(series.observe(0, END).sampleCount()).isZero();
        assertThat(series.observe(1, END).sampleCount()).isZero();
    }

    @Test
    void theLargestDelayPerStopIsKeptOnlyWhenTheBucketHasEnoughSamples() {
        List<Arrival> enough = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            enough.add(arrival(0, i % 2 == 0 ? "A" : "B", "2026-09-29T12:0%d:00Z".formatted(i + 1), 100 + i * 10));
        }
        List<Arrival> tooFew = enough.subList(0, 4);

        BucketObservation full = new ArrivalSeries(enough, thresholds).observe(0, END);
        BucketObservation few = new ArrivalSeries(tooFew, thresholds).observe(0, END);

        assertThat(full.maxDelayByStop()).containsEntry("A", 140).containsEntry("B", 130);
        assertThat(few.sampleCount()).isEqualTo(4);
        assertThat(few.maxDelayByStop()).isEmpty();
    }

    @Test
    void arrivalsMayComeInAnyOrder() {
        ArrivalSeries series = new ArrivalSeries(
                List.of(
                        arrival(0, "A", "2026-09-29T12:09:00Z", 300),
                        arrival(0, "A", "2026-09-29T12:01:00Z", 100),
                        arrival(0, "A", "2026-09-29T12:05:00Z", 200)),
                thresholds);

        assertThat(series.observe(0, END).meanDelay()).isEqualTo(200);
    }
}
