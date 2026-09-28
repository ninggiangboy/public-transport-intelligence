package dev.pti.simulator.feed;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic vehicle-to-block assignment (DOC-13 §4). Even and odd service dates draw on disjoint halves of
 * {@code vehicles.txt}, so a block running past midnight never shares a bus with the next day's first blocks.
 */
public final class VehicleAssigner {

    private final List<String> vehicles;
    private final int minLayoverSeconds;

    public VehicleAssigner(List<String> sortedVehicleIds, Duration minLayover) {
        this.vehicles = List.copyOf(sortedVehicleIds);
        this.minLayoverSeconds = Math.toIntExact(minLayover.toSeconds());
    }

    public Assignment assign(List<Block> blocks, LocalDate serviceDate) {
        boolean even = serviceDate.toEpochDay() % 2 == 0;
        int half = (vehicles.size() + 1) / 2;
        List<String> fleet = even ? vehicles.subList(0, half) : vehicles.subList(half, vehicles.size());
        int[] freeFrom = new int[fleet.size()];
        Arrays.fill(freeFrom, Integer.MIN_VALUE);

        Map<String, String> byBlock = new HashMap<>();
        int synthetic = 0;
        for (Block block : blocks) {
            if (block.rail()) {
                byBlock.put(block.blockId(), "LRV-" + (even ? "A" : "B") + "-" + block.blockId());
                continue;
            }
            int chosen = -1;
            for (int v = 0; v < fleet.size(); v++) {
                if (freeFrom[v] <= block.start()) {
                    chosen = v;
                    break;
                }
            }
            if (chosen < 0) {
                byBlock.put(block.blockId(), "BUS-" + block.blockId());
                synthetic++;
            } else {
                byBlock.put(block.blockId(), fleet.get(chosen));
                freeFrom[chosen] = block.end() + minLayoverSeconds;
            }
        }
        return new Assignment(byBlock, synthetic);
    }

    /** Vehicle ids by block id, and how many bus blocks got a synthetic {@code BUS-<block_id>}. */
    public record Assignment(Map<String, String> vehicleByBlock, int syntheticBuses) {

        public Assignment {
            vehicleByBlock = Map.copyOf(vehicleByBlock);
        }
    }
}
