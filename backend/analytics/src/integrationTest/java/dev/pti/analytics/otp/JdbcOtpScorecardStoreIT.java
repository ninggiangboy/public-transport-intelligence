package dev.pti.analytics.otp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.TripUpdateRows;
import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.otp.adapter.out.jdbc.JdbcOtpScorecardStore;
import dev.pti.analytics.otp.application.port.OtpScorecardStore.Merge;
import dev.pti.analytics.otp.domain.OtpTolerances;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The SQL of DOC-23 §8.1 against the migrated warehouse as {@code etl_writer}: hand-counted arrivals against the rows
 * it must produce (DOC-23 §18.5 AN-O-01…04, 07). Each test scores its own far-away service date, so the rows of other
 * routes that other tests leave on real dates cannot change a count.
 */
class JdbcOtpScorecardStoreIT {

    private static final OtpTolerances FIVE_MINUTES = new OtpTolerances(300, 300);

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcTemplate jdbc = new JdbcTemplate(db.dataSource);
    private final JdbcOtpScorecardStore store = new JdbcOtpScorecardStore(db.jdbc);
    private final String route = "AN6-" + UUID.randomUUID().toString().substring(0, 8);
    private final String otherRoute = "AN6B-" + UUID.randomUUID().toString().substring(0, 8);
    private final TripUpdateRows facts = new TripUpdateRows(jdbc, route);
    private final TripUpdateRows otherFacts = new TripUpdateRows(jdbc, otherRoute);
    private final LocalDate day =
            LocalDate.parse("2034-01-01").plusDays(ThreadLocalRandom.current().nextInt(0, 3000));
    private final Instant noon = day.atTime(18, 0).toInstant(ZoneOffset.UTC);

    @AfterEach
    void cleanUp() {
        facts.clear();
        otherFacts.clear();
        jdbc.update("DELETE FROM insight.insight_otp_scorecard WHERE route_id IN (?, ?)", route, otherRoute);
    }

    private Merge score(OtpTolerances tolerances) {
        return store.recompute(day, tolerances, Instant.parse("2034-01-01T03:00:00Z"), UUID.randomUUID());
    }

    private Map<String, Object> row(String routeId) {
        return db.jdbc
                .sql("SELECT * FROM insight.insight_otp_scorecard WHERE route_id = :route AND service_date = :day")
                .param("route", routeId)
                .param("day", day)
                .query()
                .singleRow();
    }

    /** Two trips: the first has three arrivals, the second two. */
    private void fiveArrivals() {
        int[] delays = {-301, -300, 0, 300, 301};
        for (int i = 0; i < delays.length; i++) {
            facts.arrival(day, noon.plusSeconds(60L * i), delays[i])
                    .trip(i < 3 ? "trip-a" : "trip-b")
                    .sequence(i + 1)
                    .insert();
        }
    }

    @Test
    void anO01TheToleranceBandIsInclusiveOnBothSides() {
        fiveArrivals();

        Merge merge = score(FIVE_MINUTES);

        assertThat(merge).isEqualTo(new Merge(1, 0));
        Map<String, Object> row = row(route);
        assertThat(row.get("early_count")).as("−301").isEqualTo(1);
        assertThat(row.get("on_time_count")).as("−300, 0 and 300").isEqualTo(3);
        assertThat(row.get("late_count")).as("301").isEqualTo(1);
        assertThat(row.get("observation_count")).isEqualTo(5);
        assertThat(row.get("trip_count")).isEqualTo(2);
        assertThat((BigDecimal) row.get("otp_percentage")).isEqualByComparingTo("60.00");
        assertThat(row.get("early_tolerance_seconds")).isEqualTo(300);
        assertThat(row.get("late_tolerance_seconds")).isEqualTo(300);
    }

    @Test
    void anO02ANarrowerEarlyToleranceIsWrittenOnTheRow() {
        fiveArrivals();

        score(new OtpTolerances(60, 300));

        Map<String, Object> row = row(route);
        assertThat(row.get("early_count")).as("−301 and −300").isEqualTo(2);
        assertThat(row.get("on_time_count")).as("0 and 300").isEqualTo(2);
        assertThat(row.get("late_count")).isEqualTo(1);
        assertThat((BigDecimal) row.get("otp_percentage")).isEqualByComparingTo("40.00");
        assertThat(row.get("early_tolerance_seconds")).isEqualTo(60);
        assertThat(row.get("late_tolerance_seconds")).isEqualTo(300);
    }

    @Test
    void anO03AnUnobservedRowAMissingDelayAndASkippedStopAreNotCounted() {
        facts.arrival(day, noon, 10).trip("t1").insert();
        facts.arrival(day, noon.plusSeconds(60), 900).trip("t2").notObserved().insert();
        facts.arrival(day, noon.plusSeconds(120), 900).trip("t3").withoutDelay().insert();
        facts.arrival(day, noon.plusSeconds(180), 900).trip("t4").skipped().insert();

        score(FIVE_MINUTES);

        Map<String, Object> row = row(route);
        assertThat(row.get("observation_count")).isEqualTo(1);
        assertThat(row.get("trip_count")).isEqualTo(1);
        assertThat(row.get("on_time_count")).isEqualTo(1);
        assertThat((BigDecimal) row.get("otp_percentage")).isEqualByComparingTo("100.00");
    }

    @Test
    void anO04AfterAReplayANewRouteIsAddedAndARouteWithoutDataIsRemoved() {
        facts.arrival(day, noon, 0).insert();
        otherFacts.arrival(day, noon, 0).insert();
        score(FIVE_MINUTES);
        assertThat(rowsOf(route, otherRoute)).hasSize(2);

        // The replay removed every arrival of the second route and added more to the first.
        otherFacts.clear();
        facts.arrival(day, noon.plusSeconds(60), 400).insert();
        Merge merge = score(FIVE_MINUTES);

        assertThat(rowsOf(route, otherRoute)).hasSize(1);
        assertThat(row(route).get("observation_count")).isEqualTo(2);
        assertThat(merge.deleted()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anO07TwoOnTimeOfThreeIsSixtySixPointSixSeven() {
        facts.arrival(day, noon, 0).insert();
        facts.arrival(day, noon.plusSeconds(60), 10).insert();
        facts.arrival(day, noon.plusSeconds(120), 400).insert();

        score(FIVE_MINUTES);

        assertThat((BigDecimal) row(route).get("otp_percentage")).isEqualByComparingTo("66.67");
    }

    @Test
    void scoringADayAgainGivesTheSameRowsExceptTheBatch() {
        fiveArrivals();
        UUID first = UUID.randomUUID();
        store.recompute(day, FIVE_MINUTES, Instant.parse("2034-01-01T03:00:00Z"), first);
        Map<String, Object> before = withoutBatch(row(route));

        Merge again = store.recompute(day, FIVE_MINUTES, Instant.parse("2034-01-01T03:00:00Z"), UUID.randomUUID());

        assertThat(withoutBatch(row(route))).isEqualTo(before);
        assertThat(again.upserted()).isGreaterThanOrEqualTo(1);
        assertThat(row(route).get("batch_id")).isNotEqualTo(first);
    }

    @Test
    void aTripThatStartedOnAnotherDayIsScoredOnItsOwnDay() {
        // 00:30 local the next morning, but the trip started on `day`: it belongs to `day`.
        facts.arrival(day, noon.plusSeconds(6 * 3600 + 1800), 0).insert();
        facts.arrival(day.plusDays(1), noon.plusSeconds(30 * 3600), 0).insert();

        score(FIVE_MINUTES);

        assertThat(row(route).get("observation_count")).isEqualTo(1);
    }

    private List<Map<String, Object>> rowsOf(String... routes) {
        return db.jdbc
                .sql("SELECT * FROM insight.insight_otp_scorecard WHERE route_id IN (:routes) AND service_date = :day")
                .param("routes", List.of(routes))
                .param("day", day)
                .query()
                .listOfRows();
    }

    private static Map<String, Object> withoutBatch(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("batch_id");
        return copy;
    }
}
