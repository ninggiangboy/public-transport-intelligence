package dev.pti.analytics.eta;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.TripUpdateRows;
import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.eta.adapter.out.jdbc.JdbcEtaAggregateStore;
import dev.pti.analytics.eta.application.port.EtaAggregateStore.Merge;
import dev.pti.analytics.eta.domain.EtaWindow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The SQL of DOC-23 §7.1 against the migrated warehouse as {@code etl_writer}: hand-computed samples and the rows the
 * statement must produce (DOC-23 §18.4 AN-E-01…06, 09, 10). The route is unique to each test, so tests do not see each
 * other's rows.
 */
class JdbcEtaAggregateStoreIT {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    /** A Tuesday; the window starts on Tuesday 8 September. */
    private static final Instant HOUR = Instant.parse("2026-10-06T12:00:00Z");

    private static final EtaWindow WINDOW = EtaWindow.of(HOUR, Duration.ofDays(28), CHICAGO);

    /** 17:10 to 17:50 local (CDT, UTC−5) on Tuesday 29 September. */
    private static final Instant TUESDAY_17H = Instant.parse("2026-09-29T22:10:00Z");

    private static final LocalDate TUESDAY = LocalDate.parse("2026-09-29");

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcTemplate jdbc = new JdbcTemplate(db.dataSource);
    private final JdbcEtaAggregateStore store = new JdbcEtaAggregateStore(db.jdbc);
    private final String route = "AN5-" + UUID.randomUUID().toString().substring(0, 8);
    private final TripUpdateRows facts = new TripUpdateRows(jdbc, route);

    @AfterEach
    void cleanUp() {
        facts.clear();
        jdbc.update("DELETE FROM insight.insight_eta_prediction WHERE route_id = ?", route);
        jdbc.update("DELETE FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'");
    }

    private Merge run() {
        return store.recompute(route, WINDOW, HOUR, UUID.randomUUID());
    }

    private List<Map<String, Object>> rows() {
        return db.jdbc.sql("""
                        SELECT stop_id, day_of_week, hour_of_day, avg_delay_seconds, median_delay_seconds,
                               p90_delay_seconds, sample_count, window_start, window_end, computed_at
                        FROM insight.insight_eta_prediction WHERE route_id = :route
                        ORDER BY stop_id, day_of_week, hour_of_day""").param("route", route).query().listOfRows();
    }

    private void arrivals(String stop, int... delays) {
        for (int i = 0; i < delays.length; i++) {
            facts.arrival(TUESDAY, TUESDAY_17H.plusSeconds(600L * i), delays[i])
                    .stop(stop)
                    .insert();
        }
    }

    @Test
    void anE01FiveArrivalsOnATuesdayAtFiveGiveTheHandComputedRow() {
        arrivals("S1", 60, 120, 180, 240, 300);

        Merge merge = run();

        assertThat(merge).isEqualTo(new Merge(1, 0));
        Map<String, Object> row = rows().getFirst();
        assertThat(rows()).hasSize(1);
        assertThat(row.get("stop_id")).isEqualTo("S1");
        assertThat(row.get("day_of_week")).as("ISO: Tuesday").isEqualTo(2);
        assertThat(row.get("hour_of_day")).isEqualTo(17);
        assertThat((BigDecimal) row.get("avg_delay_seconds")).isEqualByComparingTo("180.0");
        assertThat(row.get("median_delay_seconds")).isEqualTo(180);
        assertThat(row.get("p90_delay_seconds")).isEqualTo(300);
        assertThat(row.get("sample_count")).isEqualTo(5);
        assertThat(row.get("window_start").toString()).isEqualTo("2026-09-08");
        assertThat(row.get("window_end").toString()).isEqualTo("2026-10-06");
    }

    @Test
    void anE02AnUnobservedRowAndASkippedRowAreNotSamples() {
        arrivals("S1", 60, 120, 180, 240, 300);
        facts.arrival(TUESDAY, TUESDAY_17H.plusSeconds(3000), 900)
                .stop("S1")
                .notObserved()
                .insert();
        facts.arrival(TUESDAY, TUESDAY_17H.plusSeconds(3100), 900)
                .stop("S1")
                .skipped()
                .insert();
        facts.arrival(TUESDAY, TUESDAY_17H.plusSeconds(3200), 900)
                .stop("S1")
                .withoutDelay()
                .insert();

        run();

        Map<String, Object> row = rows().getFirst();
        assertThat(rows()).hasSize(1);
        assertThat((BigDecimal) row.get("avg_delay_seconds")).isEqualByComparingTo("180.0");
        assertThat(row.get("median_delay_seconds")).isEqualTo(180);
        assertThat(row.get("p90_delay_seconds")).isEqualTo(300);
        assertThat(row.get("sample_count")).isEqualTo(5);
    }

    @Test
    void anE03OnlyTheSampleObservedFromTheStartOfTheWindowUpToTheHourCounts() {
        Instant start = HOUR.minus(Duration.ofDays(28));
        // Scheduled 100 s before they were observed, so all three fall in the same (Tuesday, 06:xx) group.
        facts.arrival(LocalDate.parse("2026-09-08"), start.minusSeconds(100), 100)
                .arrivalAt(start)
                .stop("S1")
                .insert();
        facts.arrival(LocalDate.parse("2026-09-08"), start.minusSeconds(201), 200)
                .arrivalAt(start.minusSeconds(1))
                .stop("S1")
                .insert();
        facts.arrival(LocalDate.parse("2026-10-06"), HOUR.minusSeconds(300), 300)
                .arrivalAt(HOUR)
                .stop("S1")
                .insert();

        run();

        assertThat(rows()).hasSize(1);
        assertThat(rows().getFirst().get("sample_count"))
                .as("H − 28d counts, H − 28d − 1s and H do not")
                .isEqualTo(1);
        assertThat((BigDecimal) rows().getFirst().get("avg_delay_seconds")).isEqualByComparingTo("100.0");
    }

    @Test
    void anE04TheGroupIsTheLocalDayAndHourOfTheScheduledTimeNotTheUtcOne() {
        // 05:30Z is 00:30 CDT on Tuesday; the trip started the evening before, so its service date is Monday.
        Instant scheduled = Instant.parse("2026-09-29T05:30:00Z");
        facts.arrival(LocalDate.parse("2026-09-28"), scheduled, 120).stop("S1").insert();

        run();

        assertThat(rows()).hasSize(1);
        assertThat(rows().getFirst().get("day_of_week")).isEqualTo(2);
        assertThat(rows().getFirst().get("hour_of_day")).isEqualTo(0);
    }

    @Test
    void anE05AKeyWhoseSamplesLeftTheWindowIsDeletedAndTheRestKept() {
        jdbc.update("""
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                VALUES (?, 'S9', 3, 5, 10.0, 10, 20, 12, DATE '2026-09-01', DATE '2026-09-29',
                        TIMESTAMPTZ '2026-09-29 12:00:00Z', ?)""", route, UUID.randomUUID());
        arrivals("S1", 60, 120);

        Merge merge = run();

        assertThat(merge).isEqualTo(new Merge(1, 1));
        assertThat(rows()).extracting(row -> row.get("stop_id")).containsExactly("S1");
    }

    @Test
    void anE06RunningTheSameHourAgainGivesTheSameRowsExceptTheBatch() {
        arrivals("S1", 60, 120, 180, 240, 300);
        arrivals("S2", 10, 20, 400);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        store.recompute(route, WINDOW, HOUR, first);
        List<Map<String, Object>> before = rows();
        Merge again = store.recompute(route, WINDOW, HOUR, second);

        assertThat(rows()).isEqualTo(before);
        assertThat(again).isEqualTo(new Merge(2, 0));
        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT batch_id FROM insight.insight_eta_prediction WHERE route_id = ?",
                        UUID.class,
                        route))
                .containsExactly(second);
    }

    @Test
    void anE09TheMeanIsRoundedToOneDecimalAndTheMedianIsASampleValue() {
        arrivals("S1", 1, 2);
        arrivals("S2", 1, 2, 2);
        arrivals("S3", -1, -2);

        run();

        Map<String, Map<String, Object>> byStop = new LinkedHashMap<>();
        rows().forEach(row -> byStop.put((String) row.get("stop_id"), row));
        assertThat((BigDecimal) byStop.get("S1").get("avg_delay_seconds")).isEqualByComparingTo("1.5");
        assertThat(byStop.get("S1").get("median_delay_seconds"))
                .as("a value that exists in the sample")
                .isEqualTo(1);
        assertThat((BigDecimal) byStop.get("S2").get("avg_delay_seconds")).isEqualByComparingTo("1.7");
        assertThat((BigDecimal) byStop.get("S3").get("avg_delay_seconds")).isEqualByComparingTo("-1.5");
        assertThat(byStop.get("S3").get("median_delay_seconds")).isEqualTo(-2);
    }

    @Test
    void anE10ARouteThatLeftTheFeedIsStillListedAndItsRowsAreAllDeleted() {
        for (int hour = 6; hour <= 8; hour++) {
            jdbc.update("""
                    INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                      avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start,
                      window_end, computed_at, batch_id)
                    VALUES (?, 'S1', 1, ?, 5.0, 5, 9, 3, DATE '2026-09-01', DATE '2026-09-29',
                            TIMESTAMPTZ '2026-09-29 12:00:00Z', ?)""", route, hour, UUID.randomUUID());
        }

        assertThat(store.routeIds()).contains(route);
        Merge merge = run();

        assertThat(merge).isEqualTo(new Merge(0, 3));
        assertThat(rows()).isEmpty();
    }

    @Test
    void theRouteListNamesEveryRouteOnce() {
        // The routes of the feed and the routes with rows are joined with UNION: one entry each.
        assertThat(store.routeIds()).doesNotHaveDuplicates();
    }

    @Test
    void theWatermarkCountsObservedArrivalsAndNamesTheNewestOneInUtc() {
        // A far date that no other test writes to, so the count is this test's alone.
        LocalDate day = LocalDate.parse("2033-03-08");
        Instant scheduled = Instant.parse("2033-03-08T15:00:00Z");
        facts.arrival(day, scheduled, 60).insert();
        facts.arrival(day, scheduled.plusSeconds(600), 120).insert();
        facts.arrival(day, scheduled.plusSeconds(1200), 120).notObserved().insert();

        String watermark = store.sourceWatermark(new DateRange(day.minusDays(1), day));

        assertThat(watermark).isEqualTo("2|2033-03-08 15:12:00");
        assertThat(store.sourceWatermark(new DateRange(day.plusDays(1), day.plusDays(2))))
                .as("no data: a count of zero and no time")
                .isEqualTo("0|-");
    }

    @Test
    void theCheckpointIsOneRowThatIsReplaced() {
        assertThat(store.checkpoint()).isEmpty();

        store.saveCheckpoint("5|2026-10-06 11:59:00", HOUR, 41L);
        assertThat(store.checkpoint()).contains("5|2026-10-06 11:59:00");
        store.saveCheckpoint("6|2026-10-06 12:30:00", HOUR.plusSeconds(3600), null);

        assertThat(store.checkpoint()).contains("6|2026-10-06 12:30:00");
        Map<String, Object> row =
                jdbc.queryForMap("SELECT * FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'");
        assertThat(row.get("job_execution_id")).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'", Long.class))
                .isEqualTo(1L);
    }
}
