package dev.pti.simulator.feed;

import dev.pti.common.gtfs.GtfsFormatException;
import dev.pti.common.gtfs.GtfsRecord;
import dev.pti.common.gtfs.GtfsTime;
import dev.pti.common.gtfs.GtfsZipReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a GTFS zip into a {@link Feed} (DOC-25 §4.1). Stop times are held in primitive arrays per trip. Missing
 * {@code shape_dist_traveled} values are filled by projecting the stops onto the shape (DOC-25 §5.5).
 */
public final class FeedLoader {

    private static final Logger log = LoggerFactory.getLogger(FeedLoader.class);

    private static final List<DayOfWeek> CALENDAR_DAYS = List.of(DayOfWeek.values());

    private FeedLoader() {}

    /**
     * @param expectedSha256 the pinned hash, or {@code null} to skip the check
     * @throws GtfsFormatException when the file is missing, does not match the hash, or cannot be parsed
     */
    public static Feed load(Path zip, @Nullable String expectedSha256) {
        long started = System.nanoTime();
        if (!Files.isRegularFile(zip)) {
            throw new GtfsFormatException("GTFS feed not found: " + zip);
        }
        String sha256 = sha256(zip);
        if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(sha256)) {
            throw new GtfsFormatException(
                    "GTFS feed " + zip.getFileName() + " has sha256 " + sha256 + ", expected " + expectedSha256);
        }
        try (GtfsZipReader reader = new GtfsZipReader(zip)) {
            Feed feed = read(reader, sha256);
            int stopTimes = feed.trips().values().stream()
                    .mapToInt(TripSchedule::stopCount)
                    .sum();
            log.info(
                    "Feed loaded: sha256={}, trips={}, stopTimes={} in {}s",
                    sha256,
                    feed.trips().size(),
                    stopTimes,
                    "%.1f".formatted((System.nanoTime() - started) / 1e9));
            return feed;
        }
    }

    private static Feed read(GtfsZipReader reader, String sha256) {
        ZoneId zone = zone(reader);

        Map<String, Route> routes = new HashMap<>();
        reader.read(
                "routes.txt",
                r -> routes.put(
                        r.require("route_id"),
                        new Route(
                                r.require("route_id"),
                                r.get("route_short_name"),
                                r.get("route_long_name"),
                                r.requireInt("route_type"))));

        Map<String, Stop> stops = new HashMap<>();
        reader.read("stops.txt", r -> {
            double lat = r.getDouble("stop_lat");
            double lon = r.getDouble("stop_lon");
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                String name = r.get("stop_name");
                stops.put(r.require("stop_id"), new Stop(r.require("stop_id"), name == null ? "" : name, lat, lon));
            }
        });

        Map<String, Shape> shapes = readShapes(reader);
        ServiceCalendar calendar = readCalendar(reader);

        Map<String, TripRow> tripRows = new HashMap<>();
        reader.read("trips.txt", r -> {
            String tripId = r.require("trip_id");
            Route route = routes.get(r.require("route_id"));
            if (route == null) {
                throw r.error("unknown route_id " + r.get("route_id"));
            }
            String blockId = r.get("block_id");
            tripRows.put(
                    tripId,
                    new TripRow(
                            tripId,
                            route,
                            r.require("service_id"),
                            r.getInt("direction_id", 0),
                            blockId == null ? "trip-" + tripId : blockId,
                            r.get("shape_id")));
        });

        StopTimes stopTimes = new StopTimes();
        reader.read("stop_times.txt", r -> {
            TripRow trip = tripRows.get(r.require("trip_id"));
            if (trip == null) {
                throw r.error("unknown trip_id " + r.get("trip_id"));
            }
            Stop stop = stops.get(r.require("stop_id"));
            if (stop == null) {
                throw r.error("unknown or unlocated stop_id " + r.get("stop_id"));
            }
            String arrival = r.get("arrival_time");
            String departure = r.get("departure_time");
            String timepoint = r.get("timepoint");
            stopTimes.add(
                    stopTimes.tripIndex(trip),
                    r.requireInt("stop_sequence"),
                    stop,
                    arrival == null ? -1 : r.requireSeconds("arrival_time"),
                    departure == null ? -1 : r.requireSeconds("departure_time"),
                    r.getDouble("shape_dist_traveled"),
                    timepoint == null || !"0".equals(timepoint.strip()),
                    r.getInt("pickup_type", 0) != 1 || r.getInt("drop_off_type", 0) != 1);
        });

        Map<String, TripSchedule> trips = stopTimes.build(tripRows, shapes);

        List<String> vehicles = new ArrayList<>();
        if (reader.has("vehicles.txt")) {
            reader.read("vehicles.txt", r -> vehicles.add(r.require("vehicle_id")));
        }
        return new Feed(sha256, zone, routes, stops, trips, calendar, vehicles);
    }

    private static ZoneId zone(GtfsZipReader reader) {
        Set<String> zones = new TreeSet<>();
        reader.read("agency.txt", r -> zones.add(r.require("agency_timezone")));
        if (zones.size() != 1) {
            throw new GtfsFormatException("Expected one agency_timezone, found " + zones);
        }
        return ZoneId.of(zones.iterator().next());
    }

    private static Map<String, Shape> readShapes(GtfsZipReader reader) {
        Map<String, List<double[]>> points = new HashMap<>();
        if (!reader.has("shapes.txt")) {
            return Map.of();
        }
        reader.read(
                "shapes.txt",
                r -> points.computeIfAbsent(r.require("shape_id"), k -> new ArrayList<>())
                        .add(new double[] {
                            r.requireInt("shape_pt_sequence"),
                            r.requireDouble("shape_pt_lat"),
                            r.requireDouble("shape_pt_lon"),
                            r.getDouble("shape_dist_traveled")
                        }));
        Map<String, Shape> shapes = new HashMap<>();
        points.forEach((id, list) -> {
            list.sort((a, b) -> Double.compare(a[0], b[0]));
            double[] lat = new double[list.size()];
            double[] lon = new double[list.size()];
            double[] dist = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                lat[i] = list.get(i)[1];
                lon[i] = list.get(i)[2];
                dist[i] = list.get(i)[3];
            }
            shapes.put(id, new Shape(id, lat, lon, dist));
        });
        return shapes;
    }

    private static ServiceCalendar readCalendar(GtfsZipReader reader) {
        Map<String, ServiceCalendar.Weekly> weekly = new HashMap<>();
        if (reader.has("calendar.txt")) {
            reader.read("calendar.txt", r -> {
                Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                for (DayOfWeek day : CALENDAR_DAYS) {
                    if (r.requireInt(day.name().toLowerCase(Locale.ROOT)) == 1) {
                        days.add(day);
                    }
                }
                weekly.put(
                        r.require("service_id"),
                        new ServiceCalendar.Weekly(days, date(r, "start_date"), date(r, "end_date")));
            });
        }
        Map<LocalDate, Map<String, Boolean>> exceptions = new HashMap<>();
        if (reader.has("calendar_dates.txt")) {
            reader.read(
                    "calendar_dates.txt",
                    r -> exceptions
                            .computeIfAbsent(date(r, "date"), k -> new HashMap<>())
                            .put(r.require("service_id"), r.requireInt("exception_type") == 1));
        }
        return new ServiceCalendar(weekly, exceptions);
    }

    private static LocalDate date(GtfsRecord r, String column) {
        try {
            return GtfsTime.parseServiceDate(r.require(column));
        } catch (IllegalArgumentException e) {
            throw r.error("bad date in " + column + ": '" + r.get(column) + "'");
        }
    }

    private static String sha256(Path file) {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of()
                    .formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** A trip row, numbered in the order {@code stop_times.txt} first references it. */
    private static final class TripRow {

        final String tripId;
        final Route route;
        final String serviceId;
        final int directionId;
        final String blockId;
        final @Nullable String shapeId;
        int index = -1;

        TripRow(
                String tripId,
                Route route,
                String serviceId,
                int directionId,
                String blockId,
                @Nullable String shapeId) {
            this.tripId = tripId;
            this.route = route;
            this.serviceId = serviceId;
            this.directionId = directionId;
            this.blockId = blockId;
            this.shapeId = shapeId;
        }
    }

    /** All stop times in primitive columns; sorted per trip only once everything is read. */
    private static final class StopTimes {

        private final List<TripRow> order = new ArrayList<>();
        private int size;
        private int[] trip = new int[1024];
        private int[] sequence = new int[1024];
        private Stop[] stop = new Stop[1024];
        private int[] arrival = new int[1024];
        private int[] departure = new int[1024];
        private double[] dist = new double[1024];
        private boolean[] timepoint = new boolean[1024];
        private boolean[] serves = new boolean[1024];

        int tripIndex(TripRow row) {
            if (row.index < 0) {
                row.index = order.size();
                order.add(row);
            }
            return row.index;
        }

        void add(int t, int seq, Stop s, int arr, int dep, double d, boolean tp, boolean sv) {
            if (size == trip.length) {
                int n = size * 2;
                trip = Arrays.copyOf(trip, n);
                sequence = Arrays.copyOf(sequence, n);
                stop = Arrays.copyOf(stop, n);
                arrival = Arrays.copyOf(arrival, n);
                departure = Arrays.copyOf(departure, n);
                dist = Arrays.copyOf(dist, n);
                timepoint = Arrays.copyOf(timepoint, n);
                serves = Arrays.copyOf(serves, n);
            }
            trip[size] = t;
            sequence[size] = seq;
            stop[size] = s;
            arrival[size] = arr;
            departure[size] = dep;
            dist[size] = d;
            timepoint[size] = tp;
            serves[size] = sv;
            size++;
        }

        Map<String, TripSchedule> build(Map<String, TripRow> rows, Map<String, Shape> shapes) {
            Integer[] idx = new Integer[size];
            for (int i = 0; i < size; i++) {
                idx[i] = i;
            }
            Arrays.sort(
                    idx,
                    (a, b) -> trip[a] != trip[b]
                            ? Integer.compare(trip[a], trip[b])
                            : Integer.compare(sequence[a], sequence[b]));
            Map<String, TripSchedule> trips = new HashMap<>();
            int from = 0;
            while (from < size) {
                int to = from;
                while (to < size && trip[idx[to]] == trip[idx[from]]) {
                    to++;
                }
                TripRow row = order.get(trip[idx[from]]);
                trips.put(row.tripId, schedule(row, idx, from, to, shapes));
                from = to;
            }
            if (trips.size() != rows.size()) {
                rows.keySet().stream()
                        .filter(id -> !trips.containsKey(id))
                        .findFirst()
                        .ifPresent(id -> {
                            throw new GtfsFormatException("trips.txt: trip " + id + " has no stop_times");
                        });
            }
            return trips;
        }

        private TripSchedule schedule(TripRow row, Integer[] idx, int from, int to, Map<String, Shape> shapes) {
            int n = to - from;
            if (n < 2) {
                throw new GtfsFormatException("stop_times.txt: trip " + row.tripId + " has fewer than two stops");
            }
            int[] seq = new int[n];
            Stop[] stops = new Stop[n];
            int[] arr = new int[n];
            int[] dep = new int[n];
            double[] d = new double[n];
            boolean[] tp = new boolean[n];
            boolean[] sv = new boolean[n];
            for (int k = 0; k < n; k++) {
                int i = idx[from + k];
                seq[k] = sequence[i];
                stops[k] = stop[i];
                arr[k] = arrival[i] >= 0 ? arrival[i] : departure[i];
                dep[k] = departure[i] >= 0 ? departure[i] : arrival[i];
                d[k] = dist[i];
                tp[k] = timepoint[i];
                sv[k] = serves[i];
            }
            interpolateTimes(row.tripId, arr, dep);
            Shape shape = row.shapeId == null ? null : shapes.get(row.shapeId);
            if (shape == null) {
                shape = Shape.through("trip-" + row.tripId, stops);
                Arrays.fill(d, Double.NaN);
            }
            fillDistances(shape, stops, d);
            return new TripSchedule(
                    row.tripId,
                    row.route,
                    row.serviceId,
                    row.directionId,
                    row.blockId,
                    shape,
                    seq,
                    stops,
                    arr,
                    dep,
                    d,
                    tp,
                    sv);
        }
    }

    /** Stops without times get times interpolated between their timed neighbours, by stop index. */
    static void interpolateTimes(String tripId, int[] arr, int[] dep) {
        int n = arr.length;
        if (arr[0] < 0 || arr[n - 1] < 0) {
            throw new GtfsFormatException("stop_times.txt: trip " + tripId + " must time its first and last stop");
        }
        int last = 0;
        for (int k = 1; k < n; k++) {
            if (arr[k] >= 0) {
                for (int j = last + 1; j < k; j++) {
                    int t = dep[last] + (int) Math.round((arr[k] - dep[last]) * (double) (j - last) / (k - last));
                    arr[j] = t;
                    dep[j] = t;
                }
                last = k;
            }
        }
        for (int k = 1; k < n; k++) {
            arr[k] = Math.max(arr[k], dep[k - 1]);
            dep[k] = Math.max(dep[k], arr[k]);
        }
    }

    /** Fills missing distances by projecting stops onto the shape, keeping them non-decreasing. */
    static void fillDistances(Shape shape, Stop[] stops, double[] dist) {
        double previous = 0;
        for (int k = 0; k < stops.length; k++) {
            if (Double.isNaN(dist[k])) {
                dist[k] = shape.project(stops[k].lat(), stops[k].lon(), previous);
            }
            dist[k] = Math.max(dist[k], previous);
            previous = dist[k];
        }
    }
}
