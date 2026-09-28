package dev.pti.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ThroughputTest {

    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final Throughput throughput = new Throughput(millis::get);

    @Test
    void averagesTheLastTenWholeSeconds() {
        for (int s = 0; s < 20; s++) {
            for (int i = 0; i < 30; i++) {
                throughput.record("a");
            }
            millis.addAndGet(1_000);
        }
        throughput.record("a"); // the current second does not count yet

        assertThat(throughput.perSecond("a")).isEqualTo(30.0);
        assertThat(throughput.perSecond()).containsOnlyKeys("a");
    }

    @Test
    void forgetsSecondsOutsideTheWindow() {
        throughput.record("a");
        millis.addAndGet(5_000);
        assertThat(throughput.perSecond("a")).isEqualTo(0.1);

        millis.addAndGet(20_000);
        assertThat(throughput.perSecond("a")).isZero();
        assertThat(throughput.perSecond("unknown")).isZero();
    }
}
