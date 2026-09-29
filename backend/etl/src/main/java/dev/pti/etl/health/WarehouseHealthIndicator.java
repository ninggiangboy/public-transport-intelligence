package dev.pti.etl.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code warehouse-db} (DR-38): the circuit is closed and {@code SELECT 1} answers within a second. */
public class WarehouseHealthIndicator implements HealthIndicator {

    private static final Duration CACHE = Duration.ofSeconds(5);

    private final JdbcTemplate jdbc;
    private final CircuitBreaker breaker;
    private final Clock clock;
    private @Nullable Instant checkedAt;
    private boolean lastPing;
    private @Nullable Instant lastErrorAt;

    public WarehouseHealthIndicator(JdbcTemplate jdbc, CircuitBreaker breaker, Clock clock) {
        JdbcTemplate ping = new JdbcTemplate(jdbc.getDataSource());
        ping.setQueryTimeout(1);
        this.jdbc = ping;
        this.breaker = breaker;
        this.clock = clock;
    }

    @Override
    public Health health() {
        return current();
    }

    private Health current() {
        CircuitBreaker.State state = breaker.getState();
        boolean pingOk = ping();
        Health.Builder builder = state == CircuitBreaker.State.CLOSED && pingOk ? Health.up() : Health.down();
        builder.withDetail("circuitState", state.name());
        if (lastErrorAt != null) {
            builder.withDetail("lastErrorAt", lastErrorAt.toString());
        }
        return builder.build();
    }

    public boolean isUp() {
        return current().getStatus().equals(Status.UP);
    }

    private synchronized boolean ping() {
        Instant now = clock.instant();
        if (checkedAt != null && Duration.between(checkedAt, now).compareTo(CACHE) < 0) {
            return lastPing;
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            lastPing = true;
        } catch (RuntimeException e) {
            lastPing = false;
            lastErrorAt = now;
        }
        checkedAt = now;
        return lastPing;
    }
}
