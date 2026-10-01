package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The timeline of {@code GET /etl/jobs/summary} (DOC-32 E-31): per stream source the micro-batches in each bucket of
 * the range, and the count of batch job executions by outcome. A bucket without a micro-batch is present with zeros,
 * so that a chart shows the gap of a consumer that was stopped.
 */
public record JobSummary(
        SummaryBucket bucket, Instant from, Instant to, List<SourceSeries> stream, BatchJobCounts batchJobs) {

    /** The sources that have a micro-batch log (DOC-15 §3.2, without {@code GTFS_STATIC}). */
    public static final List<String> STREAM_SOURCES =
            List.of("GTFS_RT_VEHICLE_POSITION", "GTFS_RT_TRIP_UPDATE", "TICKETING_SALES", "TICKETING_SALE_POINTS");

    /** One bucket of one source. */
    public record Point(
            Instant bucketStart,
            int batches,
            int failedBatches,
            long read,
            long written,
            long skipped,
            long duplicate,
            int p95BatchMs) {

        static Point empty(Instant bucketStart) {
            return new Point(bucketStart, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** The buckets of one source, oldest first. */
    public record SourceSeries(String source, List<Point> points) {}

    /** Job executions of the range by outcome; {@code running} is {@code STARTING}, {@code STARTED} and {@code STOPPING}. */
    public record BatchJobCounts(int running, int completed, int failed, int stopped) {}

    /** A bucket of a source as the database returns it. */
    public record Row(String source, Point point) {}

    /** The number of points the series of all sources may have together (DOC-32 E-31). */
    public static final int MAX_POINTS = 1440 * 4;

    /** How many points a range has: its buckets times the stream sources. */
    public static int pointCount(SummaryBucket bucket, Instant from, Instant to) {
        return buckets(bucket, from, to).size() * STREAM_SOURCES.size();
    }

    private static List<Instant> buckets(SummaryBucket bucket, Instant from, Instant to) {
        long step = bucket.length().toSeconds();
        long start = Math.floorDiv(from.getEpochSecond(), step) * step;
        List<Instant> starts = new ArrayList<>();
        for (long at = start; at < to.getEpochSecond(); at += step) {
            starts.add(Instant.ofEpochSecond(at));
        }
        return starts;
    }

    /** Fills the empty buckets of every stream source and folds the job statuses into the four counts. */
    public static JobSummary of(
            SummaryBucket bucket, Instant from, Instant to, List<Row> rows, Map<String, Integer> jobStatuses) {
        Map<String, Map<Instant, Point>> bySource = new HashMap<>();
        for (Row row : rows) {
            bySource.computeIfAbsent(row.source(), key -> new HashMap<>())
                    .put(row.point().bucketStart(), row.point());
        }
        List<Instant> starts = buckets(bucket, from, to);
        List<SourceSeries> series = new ArrayList<>();
        for (String source : STREAM_SOURCES) {
            Map<Instant, Point> known = bySource.getOrDefault(source, Map.of());
            List<Point> points = new ArrayList<>();
            for (Instant start : starts) {
                points.add(known.getOrDefault(start, Point.empty(start)));
            }
            series.add(new SourceSeries(source, points));
        }
        int running = count(jobStatuses, "STARTING") + count(jobStatuses, "STARTED") + count(jobStatuses, "STOPPING");
        return new JobSummary(
                bucket,
                from,
                to,
                series,
                new BatchJobCounts(
                        running,
                        count(jobStatuses, "COMPLETED"),
                        count(jobStatuses, "FAILED"),
                        count(jobStatuses, "STOPPED")));
    }

    private static int count(Map<String, Integer> statuses, String status) {
        return statuses.getOrDefault(status, 0);
    }
}
