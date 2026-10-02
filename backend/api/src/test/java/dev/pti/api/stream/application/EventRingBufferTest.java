package dev.pti.api.stream.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.stream.domain.HubEvent;
import dev.pti.api.testing.StreamFixtures;
import dev.pti.common.events.Audience;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DOC-26 §4.3: what the ring buffer keeps, and the two replay lookups of §5. */
class EventRingBufferTest {

    private static final Instant T0 = Instant.parse("2026-09-29T21:00:00Z");

    private final EventRingBuffer buffer = new EventRingBuffer(Duration.ofMinutes(5), 3);
    private long seq;

    private HubEvent append(Instant at) {
        HubEvent event = StreamFixtures.alert(at, Audience.PUBLIC, "18").withSeq(++seq);
        buffer.append(event, at);
        return event;
    }

    @Test
    void afterAnIdGivesTheLaterEventsInArrivalOrder() {
        buffer.reset(T0);
        HubEvent first = append(T0.plusSeconds(1));
        HubEvent second = append(T0.plusSeconds(2));
        HubEvent third = append(T0.plusSeconds(3));

        assertThat(buffer.after(first.id())).contains(List.of(second, third));
        assertThat(buffer.after(third.id())).contains(List.of());
        assertThat(buffer.after("01J8ZK3V5Q7X2M4N6P8R0T2V4W")).isEmpty();
    }

    @Test
    void sinceATimeNeedsTheBufferToReachThatFarBack() {
        buffer.reset(T0);
        append(T0.plusSeconds(10));
        HubEvent later = append(T0.plusSeconds(20));

        assertThat(buffer.since(T0.plusSeconds(15))).contains(List.of(later));
        assertThat(buffer.since(T0.minusSeconds(1)))
                .as("before the prefill started")
                .isEmpty();
    }

    @Test
    void oldAndOverflowingEventsLeaveAndMoveTheCoverage() {
        buffer.reset(T0);
        HubEvent old = append(T0);
        append(T0.plus(Duration.ofMinutes(6)));
        assertThat(buffer.after(old.id())).as("older than the window").isEmpty();
        assertThat(buffer.coveredFrom()).isEqualTo(T0.plus(Duration.ofMinutes(1)));

        append(T0.plus(Duration.ofMinutes(6)).plusSeconds(1));
        append(T0.plus(Duration.ofMinutes(6)).plusSeconds(2));
        int overflow = buffer.append(
                StreamFixtures.alert(T0.plus(Duration.ofMinutes(7)), Audience.PUBLIC, null)
                        .withSeq(++seq),
                T0.plus(Duration.ofMinutes(7)));

        assertThat(overflow).isEqualTo(1);
        assertThat(buffer.size()).isEqualTo(3);
        assertThat(buffer.coveredFrom()).isEqualTo(T0.plus(Duration.ofMinutes(6)));
    }

    @Test
    void readinessWaitsForThePrefill() throws InterruptedException {
        buffer.reset(T0);
        assertThat(buffer.awaitReady(Duration.ofMillis(20))).isFalse();
        buffer.markReady();
        assertThat(buffer.awaitReady(Duration.ofMillis(20))).isTrue();
        buffer.reset(T0);
        assertThat(buffer.ready()).as("a new prefill starts unready").isFalse();
    }
}
