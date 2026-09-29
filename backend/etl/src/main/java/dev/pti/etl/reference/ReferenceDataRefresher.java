package dev.pti.etl.reference;

import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Keeps {@link ReferenceDataHolder} on the ACTIVE feed (DOC-21 §6.2): checks the active version every
 * {@code pti.etl.reference.refresh-interval}, loads a new one in the background and swaps it in; a failed load keeps
 * the old snapshot.
 */
public class ReferenceDataRefresher {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataRefresher.class);

    private final ReferenceDataLoader loader;
    private final ReferenceDataHolder holder;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;
    private final Counter errors;

    public ReferenceDataRefresher(
            ReferenceDataLoader loader,
            ReferenceDataHolder holder,
            BusinessClock clock,
            ApplicationEventPublisher events,
            MeterRegistry registry) {
        this.loader = loader;
        this.holder = holder;
        this.clock = clock;
        this.events = events;
        this.errors = Counter.builder("pti.etl.reference.refresh.errors")
                .description("Failed reloads of the reference data")
                .register(registry);
        Gauge.builder(
                        "pti.etl.reference.feed.version",
                        holder,
                        h -> h.current().map(ReferenceData::feedVersionId).orElse(0L))
                .description("feed_version_id of the reference data in use")
                .register(registry);
    }

    /** Reloads when the ACTIVE version changed; never throws. */
    public synchronized void refresh() {
        try {
            Optional<Long> active = loader.activeFeedVersionId();
            if (active.isEmpty()) {
                return;
            }
            Optional<ReferenceData> current = holder.current();
            if (current.isPresent() && current.get().feedVersionId() == active.get()) {
                warm(current.get());
                return;
            }
            ReferenceData data = loader.load(active.get());
            warm(data);
            holder.set(data);
            log.info(
                    "Reference data switched to feed version {} (was {})",
                    data.feedVersionId(),
                    current.map(c -> String.valueOf(c.feedVersionId())).orElse("none"));
            events.publishEvent(new ReferenceDataChanged(data.feedVersionId(), current.isEmpty()));
        } catch (RuntimeException e) {
            errors.increment();
            log.warn("Failed to refresh reference data; keeping the current snapshot", e);
        }
    }

    /** Today and yesterday in the agency zone are always loaded (DOC-21 §6.1). */
    private void warm(ReferenceData data) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), data.agencyZone());
        data.warm(today);
        data.warm(today.minusDays(1));
    }
}
