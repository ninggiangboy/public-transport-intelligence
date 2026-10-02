package dev.pti.etl.realtime.adapter.in.scheduling;

import dev.pti.etl.realtime.application.VehiclesBatchPublisher;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;

/** Flushes {@code vehicles.batch} every second (DOC-20 §8), and once more on shutdown (DOC-20 §10 step 3). */
public final class VehiclesBatchSchedule {

    private final VehiclesBatchPublisher publisher;

    public VehiclesBatchSchedule(VehiclesBatchPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedRate = 1000)
    void flush() {
        publisher.flush();
    }

    @PreDestroy
    void lastFlush() {
        publisher.flush();
    }
}
