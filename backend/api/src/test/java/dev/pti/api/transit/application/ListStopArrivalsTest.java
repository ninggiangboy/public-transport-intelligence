package dev.pti.api.transit.application;

import static dev.pti.apitest.TransitData.candidate;
import static dev.pti.apitest.TransitData.stop;
import static dev.pti.apitest.TransitData.withHistory;
import static dev.pti.apitest.TransitData.withTripUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.domain.Arrival;
import dev.pti.api.transit.domain.ArrivalCandidate;
import dev.pti.api.transit.domain.ArrivalSettings;
import dev.pti.api.transit.domain.Confidence;
import dev.pti.api.transit.domain.ConfidenceThresholds;
import dev.pti.api.transit.domain.StopArrivals;
import dev.pti.apitest.DirectTransactions;
import dev.pti.apitest.InMemoryTransit;
import dev.pti.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The arrivals formula of DOC-23 §7.4, in the cases of DOC-23 §18.4 (AN-A) that do not depend on the database. */
class ListStopArrivalsTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final ConfidenceThresholds THRESHOLDS = new ConfidenceThresholds(10, 30);

    private final InMemoryTransit transit = new InMemoryTransit();
    private final TestClock clock = TestClock.at(NOW);
    private final List<AsOfKind> asOfAsked = new ArrayList<>();

    @BeforeEach
    void stopExists() {
        transit.stops.put("51405", stop("51405", "Nicollet Ave & 46th St", -93.278012, 44.920401));
    }

    private ListStopArrivals useCase(boolean realtime) {
        ArrivalSettings settings =
                new ArrivalSettings(10, Duration.ofMinutes(90), realtime, Duration.ofMinutes(2), THRESHOLDS);
        return new ListStopArrivals(
                new RequireActiveFeed(transit.activeFeed),
                transit.stopReader,
                transit.arrivals,
                kind -> {
                    asOfAsked.add(kind);
                    return Optional.of(Instant.parse("2026-09-29T11:59:00Z"));
                },
                clock,
                settings,
                new DirectTransactions());
    }

    private static Instant at(String time) {
        return Instant.parse("2026-09-29T" + time + "Z");
    }

    @Test
    @DisplayName("AN-A-01 schedule 12:10:00, history avg 95.4 over 12 samples: 12:11:35, MEDIUM, sampleCount 12")
    void predictsFromHistory() {
        transit.candidates.add(withHistory(candidate("t1", at("12:10:00")), "95.4", 12));

        Arrival arrival =
                useCase(false).execute("51405", null, null).value().arrivals().get(0);

        assertThat(arrival.predictedArrival()).isEqualTo(at("12:11:35"));
        assertThat(arrival.predictedDelaySeconds()).isEqualTo(95);
        assertThat(arrival.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(arrival.sampleCount()).isEqualTo(12);
        assertThat(arrival.scheduledArrival()).isEqualTo(at("12:10:00"));
        assertThat(arrival.realtimeArrival()).isNull();
    }

    @Test
    @DisplayName("AN-A-02 without an ETA row the prediction is the schedule: sampleCount 0, NONE")
    void withoutHistory() {
        transit.candidates.add(candidate("t1", at("12:10:00")));

        Arrival arrival =
                useCase(false).execute("51405", null, null).value().arrivals().get(0);

        assertThat(arrival.predictedArrival()).isEqualTo(at("12:10:00"));
        assertThat(arrival.predictedDelaySeconds()).isZero();
        assertThat(arrival.sampleCount()).isZero();
        assertThat(arrival.confidence()).isEqualTo(Confidence.NONE);
    }

    @Test
    @DisplayName("AN-A-03 a negative average is rounded by Math.round: -30.5 s is -30 s")
    void negativeAverageRoundsHalfUp() {
        transit.candidates.add(withHistory(candidate("t1", at("12:10:00")), "-30.5", 12));

        Arrival arrival =
                useCase(false).execute("51405", null, null).value().arrivals().get(0);

        assertThat(arrival.predictedDelaySeconds()).isEqualTo(-30);
        assertThat(arrival.predictedArrival()).isEqualTo(at("12:09:30"));
    }

    @Test
    @DisplayName("AN-A-04 a trip that has already been observed at the stop is dropped, and so is a skipped one")
    void goneTripsAreDropped() {
        ArrivalCandidate observed = withTripUpdate(candidate("seen", at("12:05:00")), true, "SCHEDULED", null, null);
        ArrivalCandidate skipped = withTripUpdate(candidate("skipped", at("12:06:00")), false, "SKIPPED", null, null);
        transit.candidates.add(observed);
        transit.candidates.add(skipped);
        transit.candidates.add(candidate("coming", at("12:07:00")));

        List<Arrival> arrivals =
                useCase(false).execute("51405", null, null).value().arrivals();

        assertThat(arrivals).extracting(Arrival::tripId).containsExactly("coming");
    }

    @Test
    @DisplayName("AN-A-05 realtime on, trip update not observed and 60 s old: realtimeArrival orders the list")
    void realtimeArrivalOrdersTheList() {
        ArrivalCandidate live = withTripUpdate(
                candidate("live", at("12:10:00")), false, "SCHEDULED", at("12:12:10"), NOW.minusSeconds(60));
        transit.candidates.add(live);
        transit.candidates.add(candidate("scheduled", at("12:11:00")));

        List<Arrival> arrivals =
                useCase(true).execute("51405", null, null).value().arrivals();

        assertThat(arrivals).extracting(Arrival::tripId).containsExactly("scheduled", "live");
        assertThat(arrivals.get(1).realtimeArrival()).isEqualTo(at("12:12:10"));
    }

    @Test
    @DisplayName("AN-A-06 realtime on but the trip update is 3 minutes old: no realtimeArrival")
    void staleRealtimeIsIgnored() {
        transit.candidates.add(withTripUpdate(
                candidate("live", at("12:10:00")), false, "SCHEDULED", at("12:12:10"), NOW.minusSeconds(180)));

        Arrival arrival =
                useCase(true).execute("51405", null, null).value().arrivals().get(0);

        assertThat(arrival.realtimeArrival()).isNull();
        assertThat(arrival.predictedArrival()).isEqualTo(at("12:10:00"));
    }

    @Test
    @DisplayName("Realtime off: a fresh trip update is not shown")
    void realtimeOffIgnoresTripUpdates() {
        transit.candidates.add(withTripUpdate(
                candidate("live", at("12:10:00")), false, "SCHEDULED", at("12:12:10"), NOW.minusSeconds(10)));

        StopArrivals result = useCase(false).execute("51405", null, null).value();

        assertThat(result.realtimeEnabled()).isFalse();
        assertThat(result.arrivals().get(0).realtimeArrival()).isNull();
    }

    @Test
    @DisplayName("AN-A-08 12 candidates, one predicted before now: the first 10 of the 11 valid ones, in order")
    void limitsAfterDroppingThePast() {
        // Trip 0 is scheduled at 11:59:00 and predicted the same, so it is before now and dropped.
        IntStream.range(0, 12)
                .forEach(i -> transit.candidates.add(
                        candidate("t%02d".formatted(i), at("11:59:00").plusSeconds(i * 120L))));

        List<Arrival> arrivals =
                useCase(false).execute("51405", null, null).value().arrivals();

        assertThat(arrivals).hasSize(10);
        assertThat(arrivals).extracting(Arrival::tripId).first().isEqualTo("t01");
        assertThat(arrivals).extracting(Arrival::tripId).last().isEqualTo("t10");
    }

    @Test
    @DisplayName("A late prediction that lands after now keeps a trip that was scheduled before it")
    void predictionCanRescueAScheduledPast() {
        transit.candidates.add(withHistory(candidate("late", at("11:58:00")), "150.0", 40));

        List<Arrival> arrivals =
                useCase(false).execute("51405", null, null).value().arrivals();

        assertThat(arrivals).extracting(Arrival::tripId).containsExactly("late");
        assertThat(arrivals.get(0).confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("The order is effective time, then scheduled time, then trip id")
    void tieBreaks() {
        transit.candidates.add(candidate("b", at("12:10:00")));
        transit.candidates.add(candidate("a", at("12:10:00")));
        transit.candidates.add(withHistory(candidate("c", at("12:09:00")), "60.0", 3));

        List<Arrival> arrivals =
                useCase(false).execute("51405", null, null).value().arrivals();

        assertThat(arrivals).extracting(Arrival::tripId).containsExactly("c", "a", "b");
    }

    @Test
    @DisplayName("No trips: an empty list, not an error")
    void noTrips() {
        WithAsOf<StopArrivals> result = useCase(false).execute("51405", null, null);

        assertThat(result.value().arrivals()).isEmpty();
        assertThat(result.value().businessNow()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("The defaults are the configured limit and horizon; the query gets the horizon")
    void defaults() {
        useCase(false).execute("51405", null, null);

        assertThat(transit.lastArrivalHorizon).isEqualTo(Duration.ofMinutes(90));
        assertThat(transit.lastArrivalNow).isEqualTo(NOW);
    }

    @Test
    @DisplayName("X-Data-As-Of is the ETA computation time, or the trip update time when realtime is on")
    void asOfKinds() {
        WithAsOf<StopArrivals> off = useCase(false).execute("51405", 5, Duration.ofMinutes(30));
        WithAsOf<StopArrivals> on = useCase(true).execute("51405", 5, Duration.ofMinutes(30));

        assertThat(off.asOf()).isNotNull();
        assertThat(on.asOf()).isNotNull();
        assertThat(asOfAsked).containsExactly(AsOfKind.ETA_PREDICTION, AsOfKind.TRIP_UPDATE);
    }

    @Test
    @DisplayName("limit outside 1..30 and horizon outside PT15M..PT3H are validation errors on their fields")
    void validatesParameters() {
        ListStopArrivals useCase = useCase(false);

        assertThatThrownBy(() -> useCase.execute("51405", 0, null))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errors())
                                .extracting(error -> error.field())
                                .containsExactly("limit"));
        assertThatThrownBy(() -> useCase.execute("51405", 31, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> useCase.execute("51405", null, Duration.ofMinutes(14)))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errors())
                                .extracting(error -> error.field())
                                .containsExactly("horizon"));
        assertThatThrownBy(() -> useCase.execute("51405", 100, Duration.ofHours(4)))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errors())
                                .extracting(error -> error.field())
                                .containsExactly("limit", "horizon"));
        assertThat(useCase.execute("51405", 30, Duration.ofHours(3)).value().arrivals())
                .isEmpty();
        assertThat(useCase.execute("51405", 1, Duration.ofMinutes(15)).value().arrivals())
                .isEmpty();
    }

    @Test
    @DisplayName("An unknown stop is a 404")
    void unknownStop() {
        assertThatThrownBy(() -> useCase(false).execute("nope", null, null)).isInstanceOf(NotFoundException.class);
    }
}
