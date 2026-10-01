package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.OtpReader;
import dev.pti.api.insight.domain.OtpScorecard;
import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /insights/otp} (DOC-32 E-14): the on-time performance of routes over service days. The default range is
 * the seven days that end yesterday, the last day that has a finished scorecard, in the timezone of the ACTIVE feed.
 */
public final class GetOtpScorecard {

    /** The default range, in days (DOC-32 E-14). */
    static final int DEFAULT_DAYS = 7;

    /** The longest range, in days (DOC-31 §4.3). */
    static final int MAX_DAYS = 31;

    private final RequireActiveFeed requireActiveFeed;
    private final OtpReader scorecards;
    private final DataAsOfReader asOf;
    private final BusinessClock clock;
    private final TransactionRunner tx;

    public GetOtpScorecard(
            RequireActiveFeed requireActiveFeed,
            OtpReader scorecards,
            DataAsOfReader asOf,
            BusinessClock clock,
            TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.scorecards = scorecards;
        this.asOf = asOf;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * @throws ValidationException on {@code fromDate} or {@code toDate}: the range is reversed or longer than 31 days
     */
    public WithAsOf<OtpScorecard> execute(OtpQuery query) {
        ActiveFeed feed = requireActiveFeed.execute();
        LocalDate yesterday =
                clock.instant().atZone(feed.timezone()).toLocalDate().minusDays(1);
        LocalDate toDate = query.toDate() != null ? query.toDate() : yesterday;
        LocalDate fromDate = query.fromDate() != null ? query.fromDate() : toDate.minusDays(DEFAULT_DAYS - 1);
        check(fromDate, toDate);
        OtpScorecard scorecard =
                tx.inTransaction(() -> scorecards.read(feed, fromDate, toDate, query.routeIds(), query.routeTypes()));
        return WithAsOf.of(scorecard, asOf.asOf(AsOfKind.OTP_SCORECARD));
    }

    private static void check(LocalDate fromDate, LocalDate toDate) {
        List<FieldError> errors = new ArrayList<>();
        if (fromDate.isAfter(toDate)) {
            errors.add(new FieldError("fromDate", "must not be after toDate"));
        } else if (ChronoUnit.DAYS.between(fromDate, toDate) + 1 > MAX_DAYS) {
            errors.add(new FieldError("fromDate", "the range must not be longer than " + MAX_DAYS + " days"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("The date range is not valid.", errors);
        }
    }
}
