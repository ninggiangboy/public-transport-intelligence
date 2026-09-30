package dev.pti.analytics.bunching.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The positions of one vehicle in the loaded window, oldest first. A detector reads the positions of a route once and
 * asks each grid point for the slice it needs: the newest position in a range, and the positions of one trip.
 */
public final class VehicleTrack {

    private final String vehicleId;
    private final List<VehiclePosition> positions;

    private VehicleTrack(String vehicleId, List<VehiclePosition> positions) {
        this.vehicleId = vehicleId;
        this.positions = positions;
    }

    /** Groups positions by vehicle, in vehicle id order; each vehicle's positions are sorted by event time. */
    public static List<VehicleTrack> group(List<VehiclePosition> positions) {
        Map<String, List<VehiclePosition>> byVehicle = new TreeMap<>();
        for (VehiclePosition position : positions) {
            byVehicle
                    .computeIfAbsent(position.vehicleId(), id -> new ArrayList<>())
                    .add(position);
        }
        List<VehicleTrack> tracks = new ArrayList<>(byVehicle.size());
        byVehicle.forEach((id, list) -> {
            list.sort(Comparator.comparing(VehiclePosition::eventTimestamp));
            tracks.add(new VehicleTrack(id, List.copyOf(list)));
        });
        return List.copyOf(tracks);
    }

    public String vehicleId() {
        return vehicleId;
    }

    /** The newest position with {@code afterExclusive < event time ≤ upToInclusive}. */
    public Optional<VehiclePosition> newest(Instant afterExclusive, Instant upToInclusive) {
        int end = firstIndexAfter(upToInclusive);
        if (end == 0) {
            return Optional.empty();
        }
        VehiclePosition candidate = positions.get(end - 1);
        return candidate.eventTimestamp().isAfter(afterExclusive) ? Optional.of(candidate) : Optional.empty();
    }

    /** The positions on trip {@code tripId} with {@code fromInclusive ≤ event time ≤ toInclusive}, oldest first. */
    public List<VehiclePosition> history(String tripId, Instant fromInclusive, Instant toInclusive) {
        int end = firstIndexAfter(toInclusive);
        List<VehiclePosition> slice = new ArrayList<>();
        for (int i = end - 1; i >= 0; i--) {
            VehiclePosition position = positions.get(i);
            if (position.eventTimestamp().isBefore(fromInclusive)) {
                break;
            }
            if (position.tripId().equals(tripId)) {
                slice.add(position);
            }
        }
        Collections.reverse(slice);
        return slice;
    }

    /** Index of the first position with an event time after {@code t}; the size when there is none. */
    private int firstIndexAfter(Instant t) {
        int low = 0;
        int high = positions.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (positions.get(middle).eventTimestamp().isAfter(t)) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }
}
