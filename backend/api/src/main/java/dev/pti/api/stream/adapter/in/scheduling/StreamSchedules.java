package dev.pti.api.stream.adapter.in.scheduling;

import dev.pti.api.stream.application.EventHub;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The hub's clocks (DOC-26 §6.3, §6.4, §6.2, §7): the trailing edge of the vehicle throttle, the heartbeat, and the
 * watchdog that closes stalled writes and connections whose token expired. The watchdog runs every second so that a
 * token is cut off within a second of its {@code exp}.
 */
public final class StreamSchedules {

    private final EventHub hub;

    public StreamSchedules(EventHub hub) {
        this.hub = hub;
    }

    @Scheduled(fixedRate = 100)
    void flushVehicles() {
        hub.flushVehicles();
    }

    @Scheduled(
            fixedRateString = "${pti.api.sse.heartbeat-interval:15s}",
            initialDelayString = "${pti.api.sse.heartbeat-interval:15s}")
    void heartbeat() {
        hub.heartbeat();
    }

    @Scheduled(fixedRate = 1000)
    void watchdog() {
        hub.watchdog();
    }
}
