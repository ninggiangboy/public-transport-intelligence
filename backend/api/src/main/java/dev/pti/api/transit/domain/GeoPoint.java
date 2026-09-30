package dev.pti.api.transit.domain;

/** A WGS84 position, longitude first as in GeoJSON. */
public record GeoPoint(double lon, double lat) {}
