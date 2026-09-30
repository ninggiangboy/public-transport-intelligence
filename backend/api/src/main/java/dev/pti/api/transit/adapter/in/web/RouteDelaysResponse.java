package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.domain.ApiTime;
import dev.pti.api.transit.domain.BucketKey;
import dev.pti.api.transit.domain.DelayBucket;
import dev.pti.api.transit.domain.RouteDelays;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response of {@code GET /routes/{routeId}/delays} (DOC-32 E-03). Each item has the member that names its bucket:
 * {@code bucketStart} for {@code hour}, {@code serviceDate} for {@code day}, {@code dayOfWeek} and {@code hourOfDay}
 * for {@code hour-of-week}.
 */
public record RouteDelaysResponse(
        String routeId,
        String bucket,
        String from,
        String to,
        long earlyToleranceSeconds,
        long lateToleranceSeconds,
        List<DelayBucketResponse> items) {

    /** The delays of one bucket. */
    public record DelayBucketResponse(
            @Nullable String bucketStart,
            @Nullable String serviceDate,
            @Nullable Integer dayOfWeek,
            @Nullable Integer hourOfDay,
            BigDecimal avgDelaySeconds,
            int medianDelaySeconds,
            int p90DelaySeconds,
            long observationCount,
            BigDecimal onTimePercentage) {

        static DelayBucketResponse from(DelayBucket bucket) {
            BucketKey key = bucket.key();
            String start = key instanceof BucketKey.Hourly hourly ? ApiTime.format(hourly.start()) : null;
            String date = key instanceof BucketKey.Daily daily ? daily.date().toString() : null;
            Integer day = key instanceof BucketKey.WeekHour week ? week.dayOfWeek() : null;
            Integer hour = key instanceof BucketKey.WeekHour week ? week.hourOfDay() : null;
            return new DelayBucketResponse(
                    start,
                    date,
                    day,
                    hour,
                    bucket.avgDelaySeconds(),
                    bucket.medianDelaySeconds(),
                    bucket.p90DelaySeconds(),
                    bucket.observationCount(),
                    bucket.onTimePercentage());
        }
    }

    static RouteDelaysResponse from(RouteDelays delays) {
        return new RouteDelaysResponse(
                delays.routeId(),
                TransitParams.wire(delays.bucket()),
                ApiTime.format(delays.from()),
                ApiTime.format(delays.to()),
                delays.tolerance().early().toSeconds(),
                delays.tolerance().late().toSeconds(),
                delays.buckets().stream().map(DelayBucketResponse::from).toList());
    }
}
