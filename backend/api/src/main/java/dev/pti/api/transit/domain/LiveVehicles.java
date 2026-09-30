package dev.pti.api.transit.domain;

import java.time.Instant;
import java.util.List;

/** The snapshot of {@code GET /vehicles/live} (DOC-32 E-05), ordered by vehicle id. */
public record LiveVehicles(Instant businessNow, List<VehicleView> vehicles) {

    public LiveVehicles {
        vehicles = List.copyOf(vehicles);
    }
}
