package dev.pti.etl.reference;

/** Bounding box of the stops with {@code location_type = 0} of a feed version (DOC-14 §6.1). */
public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {

    /** True when the point lies inside the box widened by {@code margin} degrees on every side (DQ-06). */
    public boolean contains(double lat, double lon, double margin) {
        return lat >= minLat - margin && lat <= maxLat + margin && lon >= minLon - margin && lon <= maxLon + margin;
    }
}
