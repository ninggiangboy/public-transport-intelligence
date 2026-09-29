package dev.pti.etl.health;

import dev.pti.etl.reference.ReferenceDataHolder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Readiness is DOWN until an ACTIVE feed is loaded (DOC-20 §6). */
public class ReferenceDataHealthIndicator implements HealthIndicator {

    private final ReferenceDataHolder holder;

    public ReferenceDataHealthIndicator(ReferenceDataHolder holder) {
        this.holder = holder;
    }

    @Override
    public Health health() {
        return holder.current()
                .map(r -> Health.up()
                        .withDetail("feedVersionId", r.feedVersionId())
                        .build())
                .orElseGet(() ->
                        Health.down().withDetail("reason", "no active feed").build());
    }
}
