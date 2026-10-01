package dev.pti.api.insight.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.insight.domain.OtpScorecard;
import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.ServiceUnavailableException;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.apitest.DirectTransactions;
import dev.pti.apitest.InMemoryInsight;
import dev.pti.common.time.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The range of {@code GET /insights/otp} (DOC-32 E-14): defaults in the timezone of the feed, and limits. */
class GetOtpScorecardTest {

    private final InMemoryInsight insight = new InMemoryInsight();

    private GetOtpScorecard at(String instant) {
        BusinessClock clock = new BusinessClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), Duration.ZERO);
        return new GetOtpScorecard(
                new RequireActiveFeed(insight.activeFeeds),
                insight.otpReader,
                reading -> Optional.of(Instant.parse("2026-09-29T08:00:41Z")),
                clock,
                new DirectTransactions());
    }

    private static OtpQuery query(String from, String to) {
        return new OtpQuery(
                from == null ? null : LocalDate.parse(from),
                to == null ? null : LocalDate.parse(to),
                List.of(),
                List.of());
    }

    @Test
    @DisplayName("The default range is the seven days that end yesterday in Chicago, both ends included")
    void defaultRange() {
        // 2026-09-30T03:30Z is still 2026-09-29 (22:30) in Chicago, so yesterday is 2026-09-28.
        WithAsOf<OtpScorecard> result = at("2026-09-30T03:30:00Z").execute(query(null, null));

        assertThat(result.value().toDate()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(result.value().fromDate()).isEqualTo(LocalDate.parse("2026-09-22"));
        assertThat(result.asOf()).isEqualTo(Instant.parse("2026-09-29T08:00:41Z"));
    }

    @Test
    @DisplayName("Once the day has turned in Chicago, yesterday is the day before")
    void dayTurnsInTheFeedTimezone() {
        WithAsOf<OtpScorecard> result = at("2026-09-30T05:30:00Z").execute(query(null, null));

        assertThat(result.value().toDate()).isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    @DisplayName("One end given: the other is worked out from it")
    void oneEnd() {
        assertThat(at("2026-09-30T12:00:00Z")
                        .execute(query("2026-09-01", null))
                        .value()
                        .toDate())
                .isEqualTo(LocalDate.parse("2026-09-29"));
        OtpScorecard onlyTo =
                at("2026-09-30T12:00:00Z").execute(query(null, "2026-09-15")).value();
        assertThat(onlyTo.fromDate()).isEqualTo(LocalDate.parse("2026-09-09"));
    }

    @Test
    @DisplayName("A reversed range, or one of more than 31 days, is an error on fromDate; 31 days is fine")
    void limits() {
        GetOtpScorecard useCase = at("2026-09-30T12:00:00Z");

        assertThatThrownBy(() -> useCase.execute(query("2026-09-20", "2026-09-19")))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errors())
                                .extracting(error -> error.field())
                                .containsExactly("fromDate"));
        assertThatThrownBy(() -> useCase.execute(query("2026-08-01", "2026-09-01")))
                .isInstanceOf(ValidationException.class);
        assertThat(useCase.execute(query("2026-08-01", "2026-08-31")).value().routes())
                .isEmpty();
        assertThat(useCase.execute(query("2026-09-29", "2026-09-29")).value().fromDate())
                .isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    @DisplayName("Without an ACTIVE feed the timezone is unknown and the answer is a 503")
    void noFeed() {
        insight.activeFeed = Optional.empty();

        assertThatThrownBy(() -> at("2026-09-30T12:00:00Z").execute(query(null, null)))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
