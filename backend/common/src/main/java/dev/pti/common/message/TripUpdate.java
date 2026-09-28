package dev.pti.common.message;

import java.util.List;

/** A TripUpdate payload of any version (DOC-09 §4). */
public sealed interface TripUpdate extends Payload permits TripUpdateV1 {

    String tripId();

    Integer directionId();

    /** Service date as {@code YYYYMMDD}. */
    String startDate();

    String vehicleId();

    List<StopTimeUpdate> stopTimeUpdates();
}
