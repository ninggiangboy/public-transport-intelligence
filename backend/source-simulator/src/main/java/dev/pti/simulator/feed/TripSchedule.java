package dev.pti.simulator.feed;

/**
 * The immutable timetable of one trip (DOC-25 §5.2), shared by every run of the trip. Times are GTFS seconds
 * (DOC-13 §3); {@code dist} is the distance along {@link #shape()} in metres.
 */
public final class TripSchedule {

    private final String tripId;
    private final Route route;
    private final String serviceId;
    private final int directionId;
    private final String blockId;
    private final Shape shape;
    private final int[] stopSequence;
    private final Stop[] stops;
    private final int[] arrival;
    private final int[] departure;
    private final double[] dist;
    private final boolean[] timepoint;
    private final boolean[] servesPassengers;

    TripSchedule(
            String tripId,
            Route route,
            String serviceId,
            int directionId,
            String blockId,
            Shape shape,
            int[] stopSequence,
            Stop[] stops,
            int[] arrival,
            int[] departure,
            double[] dist,
            boolean[] timepoint,
            boolean[] servesPassengers) {
        this.tripId = tripId;
        this.route = route;
        this.serviceId = serviceId;
        this.directionId = directionId;
        this.blockId = blockId;
        this.shape = shape;
        this.stopSequence = stopSequence;
        this.stops = stops;
        this.arrival = arrival;
        this.departure = departure;
        this.dist = dist;
        this.timepoint = timepoint;
        this.servesPassengers = servesPassengers;
    }

    public String tripId() {
        return tripId;
    }

    public Route route() {
        return route;
    }

    public String routeId() {
        return route.id();
    }

    public String serviceId() {
        return serviceId;
    }

    public int directionId() {
        return directionId;
    }

    /** The feed's {@code block_id}, or {@code trip-<trip_id>} for a trip without one. */
    public String blockId() {
        return blockId;
    }

    public Shape shape() {
        return shape;
    }

    public int stopCount() {
        return stops.length;
    }

    public int stopSequence(int index) {
        return stopSequence[index];
    }

    public Stop stop(int index) {
        return stops[index];
    }

    public int arrival(int index) {
        return arrival[index];
    }

    public int departure(int index) {
        return departure[index];
    }

    public double dist(int index) {
        return dist[index];
    }

    public boolean timepoint(int index) {
        return timepoint[index];
    }

    /** Whether passengers may board or alight: {@code pickup_type} or {@code drop_off_type} is not 1. */
    public boolean servesPassengers(int index) {
        return servesPassengers[index];
    }

    public int firstDeparture() {
        return departure[0];
    }

    public int lastArrival() {
        return arrival[arrival.length - 1];
    }

    public int lastIndex() {
        return stops.length - 1;
    }

    @Override
    public String toString() {
        return "TripSchedule[" + tripId + " route " + route.id() + "]";
    }
}
