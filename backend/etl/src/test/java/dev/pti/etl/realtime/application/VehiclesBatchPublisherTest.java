package dev.pti.etl.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.events.UiEvent;
import dev.pti.etl.realtime.application.port.RealtimeEventSink;
import dev.pti.etl.realtime.domain.VehiclePosition;
import dev.pti.testing.TestClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DOC-20 §8, DOC-33 §5.1: one vehicles.batch per changed route and flush, the latest position per vehicle. */
class VehiclesBatchPublisherTest {

    private static final Instant T0 = Instant.parse("2026-09-29T21:19:30Z");

    private final TestClock clock = TestClock.at(T0.plusSeconds(2));
    private final List<UiEvent> sent = new ArrayList<>();
    private final List<Instant> commits = new ArrayList<>();
    private final RealtimeEventSink sink = (event, committedAt) -> {
        sent.add(event);
        commits.add(committedAt);
    };
    private final VehiclesBatchPublisher publisher = new VehiclesBatchPublisher(sink, clock);

    private static VehiclePosition at(String route, String vehicle, Instant time) {
        return new VehiclePosition(
                route, vehicle, "trip-1", 0, 44.9, -93.2, 358.0f, null, "IN_TRANSIT_TO", "51420", 14, null, time);
    }

    @Test
    @DisplayName("Each changed route gets one event with the latest position of each vehicle")
    void oneEventPerRoute() {
        publisher.add(List.of(at("18", "1203", T0), at("18", "1187", T0), at("22", "9", T0)), T0, T0.plusSeconds(1));
        publisher.add(
                List.of(at("18", "1203", T0.plusSeconds(1)), at("18", "1187", T0.minusSeconds(5))),
                T0.plusSeconds(1),
                T0.plusMillis(1500));

        publisher.flush();

        assertThat(sent).extracting(UiEvent::routeId).containsExactlyInAnyOrder("18", "22");
        UiEvent route18 =
                sent.stream().filter(e -> "18".equals(e.routeId())).findFirst().orElseThrow();
        assertThat(route18.type()).isEqualTo("vehicles.batch");
        assertThat(route18.channel()).isEqualTo(UiChannel.VEHICLES);
        assertThat(route18.audience()).isEqualTo(Audience.PUBLIC);
        assertThat(route18.key()).isEqualTo("18");
        assertThat(route18.sourceRecordTs()).as("the oldest record in it").isEqualTo(T0);
        assertThat(route18.data()).containsEntry("routeId", "18");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vehicles =
                (List<Map<String, Object>>) route18.data().get("vehicles");
        assertThat(vehicles)
                .extracting(v -> v.get("vehicleId") + "@" + v.get("eventTimestamp"))
                .containsExactly("1203@" + T0.plusSeconds(1), "1187@" + T0);
        assertThat(vehicles.getFirst())
                .doesNotContainKeys("speedMps", "occupancyStatus")
                .containsKey("bearing");
        assertThat(commits).contains(T0.plusSeconds(1));
    }

    @Test
    @DisplayName("A flush with nothing new sends nothing; positions after it go in the next one")
    void onlyChanges() {
        publisher.add(List.of(at("18", "1203", T0)), T0, T0);
        publisher.flush();
        publisher.flush();
        assertThat(sent).hasSize(1);

        publisher.add(List.of(at("18", "1203", T0.plusSeconds(1))), T0, T0);
        publisher.flush();

        assertThat(sent).hasSize(2);
    }
}
