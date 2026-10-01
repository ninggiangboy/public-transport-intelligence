package dev.pti.api.insight.application.port;

import dev.pti.api.insight.domain.OtpScorecard;
import dev.pti.api.platform.domain.ActiveFeed;
import java.time.LocalDate;
import java.util.List;

/** Reads the OTP scorecard of a range of service days (DOC-32 E-14). */
public interface OtpReader {

    /**
     * @param feed the ACTIVE feed, whose routes {@code routeTypes} refer to
     * @param routeIds the routes to keep; all when empty
     * @param routeTypes the GTFS route types to keep; all when empty
     */
    OtpScorecard read(
            ActiveFeed feed, LocalDate fromDate, LocalDate toDate, List<String> routeIds, List<Integer> routeTypes);
}
