package dev.pti.simulator.feed;

/** Spherical geometry on the scale of a city, in metres and degrees. */
public final class Geo {

    public static final double EARTH_RADIUS_M = 6_371_008.8;

    private Geo() {}

    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a =
                Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Initial bearing from the first point to the second, 0–360 degrees clockwise from north. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (degrees + 360) % 360;
    }

    /** Moves a point by metres north and east (small offsets only). */
    public static double[] offset(double lat, double lon, double northM, double eastM) {
        double dLat = Math.toDegrees(northM / EARTH_RADIUS_M);
        double dLon = Math.toDegrees(eastM / (EARTH_RADIUS_M * Math.cos(Math.toRadians(lat))));
        return new double[] {lat + dLat, lon + dLon};
    }
}
