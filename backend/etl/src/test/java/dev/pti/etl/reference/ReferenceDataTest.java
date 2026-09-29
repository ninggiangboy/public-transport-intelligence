package dev.pti.etl.reference;

import static dev.pti.etl.testing.EtlFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.testing.EtlFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** DOC-21 §6: the snapshot, the service calendar and the refresh on a feed change. */
class ReferenceDataTest {

    private static final LocalDate MONDAY = LocalDate.parse("2026-09-28");

    @Test
    void theCalendarAppliesDateChangesOverTheWeeklyPattern() {
        boolean[] weekdays = {true, true, true, true, true, false, false};
        ServiceCalendar calendar = new ServiceCalendar(
                List.of(new ServiceCalendar.Weekly("WK", weekdays, MONDAY, MONDAY.plusDays(30))),
                List.of(
                        new ServiceCalendar.DateChange("WK", MONDAY.plusDays(1), 2),
                        new ServiceCalendar.DateChange("HOL", MONDAY.plusDays(1), 1)));

        assertThat(calendar.serviceIdsOn(MONDAY)).containsExactly("WK");
        assertThat(calendar.serviceIdsOn(MONDAY.plusDays(1))).containsExactly("HOL");
        assertThat(calendar.serviceIdsOn(MONDAY.plusDays(5))).isEmpty();
        assertThat(calendar.serviceIdsOn(MONDAY.minusDays(7))).isEmpty();
    }

    @Test
    void theSnapshotAnswersTheRuleQuestions() {
        ReferenceData data = EtlFixtures.referenceData();
        LocalDate day = LocalDate.parse("2026-09-29");

        assertThat(data.hasRoute("18")).isTrue();
        assertThat(data.hasStop("51631")).isTrue();
        assertThat(data.trip(EtlFixtures.TRIP)).contains(new TripRef("18", (short) 0, "1"));
        assertThat(data.runsOn("1", day)).isTrue();
        assertThat(data.runsOn("1", LocalDate.parse("2026-10-03"))).isFalse();
        assertThat(data.scheduledArrival(EtlFixtures.TRIP, 14, day)).isEqualTo(Instant.parse("2026-09-29T21:16:00Z"));
        assertThat(data.scheduledArrival(EtlFixtures.TRIP, 99, day)).isNull();
        assertThat(data.scheduledArrival("other", 14, day)).isNull();
        assertThat(data.scheduledArrival(EtlFixtures.TRIP, 14, LocalDate.parse("2026-10-03")))
                .isNull();
        assertThat(data.bbox().contains(44.9, -93.2, 0)).isTrue();
        assertThat(data.bbox().contains(45.05, -93.2, 0.1)).isTrue();
        assertThat(data.bbox().contains(45.25, -93.2, 0.1)).isFalse();
    }

    @Test
    void refreshSwitchesOnlyWhenTheActiveVersionChanges() {
        ReferenceDataLoader loader = mock(ReferenceDataLoader.class);
        ReferenceDataHolder holder = new ReferenceDataHolder();
        List<Object> events = new ArrayList<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReferenceDataRefresher refresher = new ReferenceDataRefresher(
                loader,
                holder,
                new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO),
                events::add,
                meters);

        when(loader.activeFeedVersionId()).thenReturn(Optional.empty());
        refresher.refresh();
        assertThat(holder.isLoaded()).isFalse();

        ReferenceData v3 = EtlFixtures.referenceData();
        when(loader.activeFeedVersionId()).thenReturn(Optional.of(3L));
        when(loader.load(3L)).thenReturn(v3);
        refresher.refresh();
        refresher.refresh();
        assertThat(holder.current()).contains(v3);
        verify(loader, times(1)).load(3L);
        assertThat(events).containsExactly(new ReferenceDataChanged(3, true));
        assertThat(meters.find("pti.etl.reference.feed.version").gauge().value())
                .isEqualTo(3.0);

        when(loader.activeFeedVersionId()).thenReturn(Optional.of(4L));
        when(loader.load(4L)).thenThrow(new IllegalStateException("broken"));
        refresher.refresh();
        assertThat(holder.current()).contains(v3);
        assertThat(meters.find("pti.etl.reference.refresh.errors").counter().count())
                .isEqualTo(1);
        verify(loader, never()).load(5L);
    }
}
