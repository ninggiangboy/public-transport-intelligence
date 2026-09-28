package dev.pti.common.message;

import org.jspecify.annotations.Nullable;

/** A VehiclePosition payload of any version (DOC-09 §3). */
public sealed interface VehiclePosition extends Payload permits VehiclePositionV1, VehiclePositionV2 {

    String vehicleId();

    String tripId();

    Integer directionId();

    /** Service date as {@code YYYYMMDD} (DR-08). */
    String startDate();

    Double lat();

    Double lon();

    @Nullable
    Double bearing();

    @Nullable
    Double speedMps();

    Integer currentStopSequence();

    String stopId();

    VehicleStopStatus currentStatus();

    /** Absent before v2. */
    default @Nullable OccupancyStatus occupancy() {
        return null;
    }
}
