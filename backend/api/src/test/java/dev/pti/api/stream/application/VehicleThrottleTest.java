package dev.pti.api.stream.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.stream.domain.HubEvent;
import dev.pti.api.testing.StreamFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DOC-26 §6.3, RT-08, SE-09: one vehicles.batch per route and second, merged by vehicle. */
class VehicleThrottleTest {

    private static final Instant T0 = Instant.parse("2026-09-29T21:00:00Z");

    private final VehicleThrottle throttle = new VehicleThrottle(1);

    @Test
    @DisplayName("RT-08 five batches of one route in a second give the leading one and one merged trailing one")
    void leadingAndTrailingEdge() {
        HubEvent first = StreamFixtures.vehicles(T0, "18", "1203", T0.minusSeconds(5));
        assertThat(throttle.offer(first, T0)).isSameAs(first);
        HubEvent last = null;
        for (int i = 1; i <= 4; i++) {
            last = StreamFixtures.vehicles(T0.plusMillis(200L * i), "18", "1203", T0.plusMillis(200L * i));
            assertThat(throttle.offer(last, T0.plusMillis(200L * i))).isNull();
        }
        assertThat(throttle.due(T0.plusMillis(900))).isEmpty();

        List<HubEvent> due = throttle.due(T0.plusSeconds(1));

        assertThat(due).hasSize(1);
        assertThat(due.getFirst().id()).isEqualTo(last.id());
        assertThat(vehicles(due.getFirst())).hasSize(1);
        assertThat(vehicles(due.getFirst()).getFirst().get("eventTimestamp"))
                .isEqualTo(T0.plusMillis(800).toString());
        assertThat(throttle.due(T0.plusSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("SE-09 a merge keeps every vehicle, the newer position winning, and the oldest record time")
    void mergeByVehicle() {
        HubEvent a = StreamFixtures.vehicles(T0, "18", "1203", T0.plusSeconds(2));
        HubEvent b = StreamFixtures.vehicles(T0.plusMillis(100), "18", "1203", T0.plusSeconds(1));
        HubEvent c = StreamFixtures.vehicles(T0.plusMillis(200), "18", "1187", T0.plusSeconds(3));

        HubEvent merged = VehicleThrottle.merge(VehicleThrottle.merge(a, b), c);

        assertThat(vehicles(merged))
                .extracting(v -> v.get("vehicleId") + "@" + v.get("eventTimestamp"))
                .containsExactly("1203@" + T0.plusSeconds(2), "1187@" + T0.plusSeconds(3));
        assertThat(merged.sourceRecordTs()).isEqualTo(T0.plusSeconds(1));
        assertThat(merged.id()).isEqualTo(c.id());
    }

    @Test
    void routesAreThrottledApart() {
        HubEvent r18 = StreamFixtures.vehicles(T0, "18", "1", T0);
        HubEvent r22 = StreamFixtures.vehicles(T0, "22", "2", T0);

        assertThat(throttle.offer(r18, T0)).isNotNull();
        assertThat(throttle.offer(r22, T0)).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vehicles(HubEvent event) {
        return (List<Map<String, Object>>) event.data().get("vehicles");
    }
}
