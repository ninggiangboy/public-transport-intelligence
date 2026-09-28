package dev.pti.common.message;

/** The payload of an envelope; one implementation per {@link PayloadType}. */
public sealed interface Payload permits VehiclePosition, TripUpdate {

    String routeId();
}
