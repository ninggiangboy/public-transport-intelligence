package dev.pti.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UiEventTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:19:31.020Z");
    private static final Instant SOURCE = Instant.parse("2026-09-29T21:19:30.107Z");

    private static UiEvent event(Map<String, Object> data) {
        return UiEvent.of(
                NOW, "bunching.opened", UiChannel.ALERTS, Audience.OPERATIONS, "episode-1", "18", SOURCE, data);
    }

    @Test
    @DisplayName("of() keeps the given fields and adds a 26 character ULID")
    void ofKeepsFieldsAndGeneratesAUlid() {
        UiEvent event = event(Map.of("id", "episode-1", "routeId", "18"));

        assertThat(event.id()).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(event.type()).isEqualTo("bunching.opened");
        assertThat(event.channel()).isEqualTo(UiChannel.ALERTS);
        assertThat(event.audience()).isEqualTo(Audience.OPERATIONS);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.sourceRecordTs()).isEqualTo(SOURCE);
        assertThat(event.routeId()).isEqualTo("18");
        assertThat(event.key()).isEqualTo("episode-1");
        assertThat(event.data()).containsEntry("routeId", "18");
    }

    @Test
    @DisplayName("Ids from consecutive of() calls are unique and sort in creation order")
    void idsAreMonotonic() {
        List<String> ids = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> event(Map.of()).id())
                .toList();

        assertThat(ids).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void routeIdAndSourceRecordTimestampMayBeAbsent() {
        UiEvent event = UiEvent.of(NOW, "job.run", UiChannel.JOBS, Audience.ENGINEERING, "job:7", null, null, Map.of());

        assertThat(event.routeId()).isNull();
        assertThat(event.sourceRecordTs()).isNull();
    }

    @Test
    void dataIsCopiedKeepsItsOrderAndCannotBeChanged() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("b", 1);
        source.put("a", null);
        UiEvent event = event(source);
        source.put("c", 3);

        assertThat(event.data()).containsOnlyKeys("b", "a").isNotSameAs(source);
        assertThat(event.data().keySet()).containsExactly("b", "a");
        assertThatThrownBy(() -> event.data().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankTextFieldsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UiEvent.of(NOW, " ", UiChannel.ALERTS, Audience.PUBLIC, "k", null, null, Map.of()))
                .withMessageContaining("type");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UiEvent.of(NOW, "t", UiChannel.ALERTS, Audience.PUBLIC, "", null, null, Map.of()))
                .withMessageContaining("key");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new UiEvent("", "t", UiChannel.ALERTS, Audience.PUBLIC, NOW, null, null, "k", Map.of()))
                .withMessageContaining("id");
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UiEvent.of(NOW, "t", null, Audience.PUBLIC, "k", null, null, Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UiEvent.of(NOW, "t", UiChannel.ALERTS, null, "k", null, null, Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UiEvent.of(NOW, "t", UiChannel.ALERTS, Audience.PUBLIC, "k", null, null, null));
    }

    @Test
    @DisplayName("data above 64 KiB is a programming error")
    void oversizedDataIsRejected() {
        Map<String, Object> data = Map.of("blob", "x".repeat(UiEvent.MAX_DATA_BYTES));

        assertThatIllegalArgumentException().isThrownBy(() -> event(data)).withMessageContaining("bunching.opened");
    }

    @Test
    @DisplayName("A vehicles.batch of 60 vehicles (about 15 KiB) is accepted, nested collections included")
    void typicalBatchIsAccepted() {
        List<Map<String, Object>> vehicles = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Map<String, Object> vehicle = new HashMap<>();
            vehicle.put("vehicleId", "veh-" + i);
            vehicle.put("lat", 44.9778 + i / 1000.0);
            vehicle.put("lon", -93.265);
            vehicle.put("tripId", "trip-" + i);
            vehicle.put("stale", false);
            vehicle.put("nextStop", null);
            vehicles.add(vehicle);
        }

        UiEvent event = UiEvent.of(
                NOW,
                "vehicles.batch",
                UiChannel.VEHICLES,
                Audience.PUBLIC,
                "18",
                "18",
                SOURCE,
                Map.of("vehicles", vehicles));

        assertThat(event.data()).containsKey("vehicles");
    }

    @Test
    void absurdNestingIsRejected() {
        Object nested = "leaf";
        for (int i = 0; i < 50; i++) {
            nested = Map.of("n", nested);
        }
        Object deep = nested;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> event(Map.of("deep", deep)))
                .withMessageContaining("nested");
    }

    @Test
    void channelsHaveLowercaseWireNamesThatParseBack() {
        for (UiChannel channel : UiChannel.values()) {
            assertThat(channel.wireName()).isEqualTo(channel.name().toLowerCase(java.util.Locale.ROOT));
            assertThat(UiChannel.fromWireName(channel.wireName())).isEqualTo(channel);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> UiChannel.fromWireName("weather"));
    }
}
