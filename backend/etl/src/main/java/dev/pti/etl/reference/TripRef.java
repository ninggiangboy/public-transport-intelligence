package dev.pti.etl.reference;

/** What the realtime rules need to know about a scheduled trip (DOC-21 §6.1). */
public record TripRef(String routeId, short directionId, String serviceId) {}
