package dev.pti.api.insight.application;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the OTP scorecard is asked for (DOC-32 E-14): a range of service days, both ends included, on some routes and
 * some GTFS route types (all when empty). A missing date is worked out from the default: the seven days that end
 * yesterday in the timezone of the feed.
 */
public record OtpQuery(
        @Nullable LocalDate fromDate, @Nullable LocalDate toDate, List<String> routeIds, List<Integer> routeTypes) {

    public OtpQuery {
        routeIds = List.copyOf(routeIds);
        routeTypes = List.copyOf(routeTypes);
    }
}
