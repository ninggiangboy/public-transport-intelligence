package dev.pti.etl.gtfs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * The GTFS files the job loads (DOC-13 §2.3, §2.4): required and mapped columns, the target table, and the mapping
 * of one row. Columns neither mapped nor required are reported as {@code extra_columns} (GV-14).
 */
public enum GtfsTable {
    AGENCY(
            "agency.txt",
            "loadAgency",
            500,
            List.of("agency_name", "agency_url", "agency_timezone"),
            List.of("agency_id", "agency_lang", "agency_phone", "agency_fare_url", "agency_email"),
            """
            INSERT INTO dw.dim_agency (feed_version_id, agency_id, agency_name, agency_url, agency_timezone,
                                       agency_lang, agency_phone, agency_fare_url)
            VALUES (:feed_version_id, :agency_id, :agency_name, :agency_url, :agency_timezone,
                    :agency_lang, :agency_phone, :agency_fare_url)
            """,
            (row, v) -> map(
                    "feed_version_id", v,
                    "agency_id", orEmpty(row.text("agency_id")),
                    "agency_name", row.required("agency_name"),
                    "agency_url", row.text("agency_url"),
                    "agency_timezone", row.required("agency_timezone"),
                    "agency_lang", row.text("agency_lang"),
                    "agency_phone", row.text("agency_phone"),
                    "agency_fare_url", row.text("agency_fare_url"))),
    ROUTES(
            "routes.txt",
            "loadRoutes",
            500,
            List.of("route_id", "route_type"),
            List.of(
                    "agency_id",
                    "route_short_name",
                    "route_long_name",
                    "route_desc",
                    "route_color",
                    "route_text_color",
                    "route_sort_order"),
            """
            INSERT INTO dw.dim_route (feed_version_id, route_id, agency_id, route_short_name, route_long_name,
                                      route_desc, display_name, route_type, route_color, route_text_color,
                                      route_sort_order)
            VALUES (:feed_version_id, :route_id, :agency_id, :route_short_name, :route_long_name, :route_desc,
                    :display_name, :route_type, :route_color, :route_text_color, :route_sort_order)
            """,
            (row, v) -> {
                String id = row.required("route_id");
                String shortName = row.text("route_short_name");
                String longName = row.text("route_long_name");
                return map(
                        "feed_version_id", v,
                        "route_id", id,
                        "agency_id", orEmpty(row.text("agency_id")),
                        "route_short_name", shortName,
                        "route_long_name", longName,
                        "route_desc", row.text("route_desc"),
                        "display_name", shortName != null ? shortName : longName != null ? longName : id,
                        "route_type", row.requiredInt("route_type"),
                        "route_color", row.color("route_color"),
                        "route_text_color", row.color("route_text_color"),
                        "route_sort_order", row.integer("route_sort_order"));
            }),
    STOPS(
            "stops.txt",
            "loadStops",
            500,
            List.of("stop_id"),
            List.of(
                    "stop_code",
                    "stop_name",
                    "stop_desc",
                    "stop_lat",
                    "stop_lon",
                    "location_type",
                    "parent_station",
                    "wheelchair_boarding",
                    "platform_code"),
            """
            INSERT INTO dw.dim_stop (feed_version_id, stop_id, stop_code, stop_name, stop_desc, lat, lon,
                                     location_type, parent_station, wheelchair_boarding, platform_code)
            VALUES (:feed_version_id, :stop_id, :stop_code, :stop_name, :stop_desc, :lat, :lon, :location_type,
                    :parent_station, :wheelchair_boarding, :platform_code)
            """,
            (row, v) -> map(
                    "feed_version_id", v,
                    "stop_id", row.required("stop_id"),
                    "stop_code", row.text("stop_code"),
                    "stop_name", row.text("stop_name"),
                    "stop_desc", row.text("stop_desc"),
                    "lat", row.decimal("stop_lat"),
                    "lon", row.decimal("stop_lon"),
                    "location_type", row.intOrDefault("location_type", 0),
                    "parent_station", row.text("parent_station"),
                    "wheelchair_boarding", row.intOrDefault("wheelchair_boarding", 0),
                    "platform_code", row.text("platform_code"))),
    CALENDAR(
            "calendar.txt",
            "loadCalendar",
            500,
            List.of(
                    "service_id",
                    "monday",
                    "tuesday",
                    "wednesday",
                    "thursday",
                    "friday",
                    "saturday",
                    "sunday",
                    "start_date",
                    "end_date"),
            List.of(),
            """
            INSERT INTO dw.gtfs_calendar (feed_version_id, service_id, monday, tuesday, wednesday, thursday, friday,
                                          saturday, sunday, start_date, end_date)
            VALUES (:feed_version_id, :service_id, :monday, :tuesday, :wednesday, :thursday, :friday, :saturday,
                    :sunday, :start_date, :end_date)
            """,
            (row, v) -> map(
                    "feed_version_id", v,
                    "service_id", row.required("service_id"),
                    "monday", row.flag("monday"),
                    "tuesday", row.flag("tuesday"),
                    "wednesday", row.flag("wednesday"),
                    "thursday", row.flag("thursday"),
                    "friday", row.flag("friday"),
                    "saturday", row.flag("saturday"),
                    "sunday", row.flag("sunday"),
                    "start_date", row.date("start_date"),
                    "end_date", row.date("end_date"))),
    CALENDAR_DATES(
            "calendar_dates.txt",
            "loadCalendarDates",
            500,
            List.of("service_id", "date", "exception_type"),
            List.of(),
            """
            INSERT INTO dw.gtfs_calendar_date (feed_version_id, service_id, date, exception_type)
            VALUES (:feed_version_id, :service_id, :date, :exception_type)
            """,
            (row, v) -> map(
                    "feed_version_id", v,
                    "service_id", row.required("service_id"),
                    "date", row.date("date"),
                    "exception_type", row.requiredInt("exception_type"))),
    SHAPES(
            "shapes.txt",
            "loadShapes",
            1000,
            List.of("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"),
            List.of("shape_dist_traveled"),
            """
            INSERT INTO dw.gtfs_shape (feed_version_id, shape_id, shape_pt_sequence, lat, lon, shape_dist_traveled)
            VALUES (:feed_version_id, :shape_id, :shape_pt_sequence, :lat, :lon, :shape_dist_traveled)
            """,
            (row, v) -> map(
                    "feed_version_id", v,
                    "shape_id", row.required("shape_id"),
                    "shape_pt_sequence", row.requiredInt("shape_pt_sequence"),
                    "lat", row.requiredDecimal("shape_pt_lat"),
                    "lon", row.requiredDecimal("shape_pt_lon"),
                    "shape_dist_traveled", row.decimal("shape_dist_traveled"))),
    TRIPS(
            "trips.txt",
            "loadTrips",
            500,
            List.of("route_id", "service_id", "trip_id"),
            List.of("trip_headsign", "direction_id", "direction", "block_id", "shape_id", "wheelchair_accessible"),
            """
            INSERT INTO dw.gtfs_trip (feed_version_id, trip_id, route_id, service_id, direction_id, direction_label,
                                      trip_headsign, block_id, shape_id, wheelchair_accessible)
            VALUES (:feed_version_id, :trip_id, :route_id, :service_id, :direction_id, :direction_label,
                    :trip_headsign, :block_id, :shape_id, :wheelchair_accessible)
            """,
            (row, v) -> map(
                    "feed_version_id", v,
                    "trip_id", row.required("trip_id"),
                    "route_id", row.required("route_id"),
                    "service_id", row.required("service_id"),
                    "direction_id", row.requiredInt("direction_id"),
                    "direction_label", row.text("direction"),
                    "trip_headsign", row.text("trip_headsign"),
                    "block_id", row.text("block_id"),
                    "shape_id", row.text("shape_id"),
                    "wheelchair_accessible", row.intOrDefault("wheelchair_accessible", 0))),
    STOP_TIMES(
            "stop_times.txt",
            "loadStopTimes",
            1000,
            List.of("trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"),
            List.of("pickup_type", "drop_off_type", "timepoint", "shape_dist_traveled"),
            """
            INSERT INTO dw.gtfs_stop_time (feed_version_id, trip_id, stop_sequence, stop_id, arrival_seconds,
                                           departure_seconds, pickup_type, drop_off_type, timepoint,
                                           shape_dist_traveled)
            VALUES (:feed_version_id, :trip_id, :stop_sequence, :stop_id, :arrival_seconds, :departure_seconds,
                    :pickup_type, :drop_off_type, :timepoint, :shape_dist_traveled)
            """,
            (row, v) -> map(
                    "feed_version_id", v,
                    "trip_id", row.required("trip_id"),
                    "stop_sequence", row.requiredInt("stop_sequence"),
                    "stop_id", row.required("stop_id"),
                    "arrival_seconds", row.seconds("arrival_time"),
                    "departure_seconds", row.seconds("departure_time"),
                    "pickup_type", row.intOrDefault("pickup_type", 0),
                    "drop_off_type", row.intOrDefault("drop_off_type", 0),
                    "timepoint", row.timepoint("timepoint"),
                    "shape_dist_traveled", row.decimal("shape_dist_traveled"))),
    VEHICLES(
            "vehicles.txt",
            "loadVehicles",
            500,
            List.of("vehicle_id"),
            List.of(
                    "vehicle_label",
                    "vehicle_description",
                    "seated_capacity",
                    "standing_capacity",
                    "low_floor",
                    "wheelchair_access",
                    "fuel"),
            null,
            (row, v) -> map(
                    "vehicle_id", row.required("vehicle_id"),
                    "vehicle_label", row.text("vehicle_label"),
                    "vehicle_model", row.text("vehicle_description"),
                    "seated_capacity", row.integer("seated_capacity"),
                    "standing_capacity", row.integer("standing_capacity"),
                    "low_floor", row.bool("low_floor"),
                    "wheelchair_access", row.text("wheelchair_access"),
                    "fuel", row.text("fuel"))),
    FEED_INFO(
            "feed_info.txt",
            "loadFeedInfo",
            1,
            List.of("feed_publisher_name"),
            List.of("feed_version"),
            null,
            (row, v) -> map(
                    "publisher_name", row.text("feed_publisher_name"),
                    "publisher_feed_version", row.text("feed_version")));

    /** Load steps in foreign key order (DOC-21 §3.2). */
    public static final List<GtfsTable> LOAD_ORDER =
            List.of(AGENCY, ROUTES, STOPS, CALENDAR, CALENDAR_DATES, SHAPES, TRIPS, STOP_TIMES);

    /** GV-01: {@code calendar.txt} or {@code calendar_dates.txt} is required too, checked apart. */
    public static final Set<GtfsTable> REQUIRED = Set.of(AGENCY, ROUTES, STOPS, TRIPS, STOP_TIMES, SHAPES);

    private final String file;
    private final String stepName;
    private final int chunkSize;
    private final List<String> requiredColumns;
    private final List<String> optionalColumns;
    private final @Nullable String insertSql;
    private final BiFunction<GtfsRow, Long, Map<String, @Nullable Object>> mapper;

    GtfsTable(
            String file,
            String stepName,
            int chunkSize,
            List<String> requiredColumns,
            List<String> optionalColumns,
            @Nullable String insertSql,
            BiFunction<GtfsRow, Long, Map<String, @Nullable Object>> mapper) {
        this.file = file;
        this.stepName = stepName;
        this.chunkSize = chunkSize;
        this.requiredColumns = requiredColumns;
        this.optionalColumns = optionalColumns;
        this.insertSql = insertSql;
        this.mapper = mapper;
    }

    public String file() {
        return file;
    }

    /** {@code agency} for {@code agency.txt}, as used in the report. */
    public String baseName() {
        return file.substring(0, file.length() - ".txt".length());
    }

    public String stepName() {
        return stepName;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public List<String> requiredColumns() {
        return requiredColumns;
    }

    /** Columns the job reads; everything else in the header is an extra column (GV-14). */
    public boolean isMapped(String column) {
        return requiredColumns.contains(column) || optionalColumns.contains(column);
    }

    public String insertSql() {
        if (insertSql == null) {
            throw new IllegalStateException(file + " is not loaded by a plain INSERT");
        }
        return insertSql;
    }

    public GtfsInsert map(GtfsRow row, long feedVersionId) {
        return GtfsInsert.of(row, mapper.apply(row, feedVersionId));
    }

    public static java.util.Optional<GtfsTable> byFile(String file) {
        for (GtfsTable t : values()) {
            if (t.file.equals(file)) {
                return java.util.Optional.of(t);
            }
        }
        return java.util.Optional.empty();
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** A map that keeps null values, which {@code Map.of} does not allow. */
    private static Map<String, @Nullable Object> map(@Nullable Object... keysAndValues) {
        Map<String, @Nullable Object> map = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
