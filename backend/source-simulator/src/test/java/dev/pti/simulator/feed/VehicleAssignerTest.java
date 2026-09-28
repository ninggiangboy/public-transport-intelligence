package dev.pti.simulator.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** DOC-13 §4 and DOC-25 T-04. */
class VehicleAssignerTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 29);

    private final ServiceDays days = new ServiceDays(
            Feeds.real(), new ServiceDateMapper(Feeds.real().calendar(), "auto"), Duration.ofMinutes(10));

    @Test
    void aWeekdayUses597BusesAnd24LightRailVehicles() {
        ServiceDay day = days.day(TUESDAY).orElseThrow();

        long buses = day.blocks().stream()
                .filter(b -> !b.block().rail())
                .map(ServiceDay.AssignedBlock::vehicleId)
                .distinct()
                .count();
        long rail = day.blocks().stream().filter(b -> b.block().rail()).count();

        assertThat(buses).isEqualTo(597);
        assertThat(rail).isEqualTo(24);
        assertThat(day.syntheticBuses()).isZero();
        assertThat(day.blocks().stream().filter(b -> b.block().rail()).map(ServiceDay.AssignedBlock::vehicleId))
                .allMatch(id -> id.startsWith("LRV-"));
    }

    @Test
    void noVehicleRunsTwoBlocksAtOnceOverThreeDays() {
        Map<String, List<Instant[]>> byVehicle = new HashMap<>();
        for (int d = 0; d < 3; d++) {
            LocalDate date = TUESDAY.plusDays(d);
            for (ServiceDay.AssignedBlock b : days.day(date).orElseThrow().blocks()) {
                byVehicle.computeIfAbsent(b.vehicleId(), k -> new ArrayList<>()).add(new Instant[] {
                    days.instantOf(date, b.block().start()),
                    days.instantOf(date, b.block().end())
                });
            }
        }

        byVehicle.forEach((vehicle, spans) -> {
            spans.sort((a, b) -> a[0].compareTo(b[0]));
            for (int i = 1; i < spans.size(); i++) {
                assertThat(spans.get(i)[0]).as("vehicle %s", vehicle).isAfter(spans.get(i - 1)[1]);
            }
        });
    }

    @Test
    void isDeterministic() {
        ServiceDays again = new ServiceDays(
                Feeds.real(), new ServiceDateMapper(Feeds.real().calendar(), "auto"), Duration.ofMinutes(10));

        assertThat(again.day(TUESDAY).orElseThrow()).isEqualTo(days.day(TUESDAY).orElseThrow());
    }

    @Test
    void honoursTheMinimumLayoverAndFallsBackToSyntheticIds() {
        VehicleAssigner assigner = new VehicleAssigner(List.of("a", "b", "c", "d"), Duration.ofMinutes(10));
        List<Block> blocks =
                List.of(block("1", 0, 1000), block("2", 1500, 3000), block("3", 1600, 2000), block("4", 1700, 2000));

        // Even day: vehicles a and b. Block 2 reuses a (1000 + 600 <= 1500 is false, so b); 3 and 4 find none free.
        VehicleAssigner.Assignment even = assigner.assign(blocks, LocalDate.ofEpochDay(0));
        VehicleAssigner.Assignment odd = assigner.assign(blocks, LocalDate.ofEpochDay(1));

        assertThat(even.vehicleByBlock())
                .containsEntry("1", "a")
                .containsEntry("2", "b")
                .containsEntry("3", "a");
        assertThat(even.vehicleByBlock()).containsEntry("4", "BUS-4");
        assertThat(even.syntheticBuses()).isEqualTo(1);
        assertThat(odd.vehicleByBlock()).containsEntry("1", "c");
    }

    private static Block block(String id, int start, int end) {
        return new Block(id, List.of(), start, end, false);
    }
}
