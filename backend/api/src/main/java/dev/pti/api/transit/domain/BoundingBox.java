package dev.pti.api.transit.domain;

/** A map window: {@code minLon,minLat,maxLon,maxLat} (DOC-32 E-06). */
public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {

    /** The largest area a window may cover, in square degrees: about 40 x 28 km in Minneapolis (DOC-32 E-06). */
    public static final double MAX_AREA_SQUARE_DEGREES = 0.25;

    public BoundingBox {
        if (!(minLon < maxLon) || !(minLat < maxLat)) {
            throw new IllegalArgumentException("The minimum of a bounding box must be below its maximum");
        }
    }

    public double area() {
        return (maxLon - minLon) * (maxLat - minLat);
    }
}
