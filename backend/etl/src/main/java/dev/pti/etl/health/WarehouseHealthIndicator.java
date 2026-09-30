package dev.pti.etl.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code warehouse-db} (DR-38): the circuit is closed and {@code SELECT 1} answers within a second.
 *
 * <p>Only the health endpoint pings the database, at most every 5 seconds and one ping at a time: while one runs,
 * other callers get the last result. {@link #isUp()} never touches the database, because it backs a gauge, and a
 * scrape that waits for a connection makes the whole app look down while the warehouse is (DOC-28 §2).
 */
public class WarehouseHealthIndicator implements HealthIndicator {

    private static final Duration CACHE = Duration.ofSeconds(5);

    private final JdbcTemplate jdbc;
    private final CircuitBreaker breaker;
    private final Clock clock;
    private final AtomicBoolean pinging = new AtomicBoolean();
    private volatile @Nullable Instant checkedAt;
    private volatile boolean lastPing;
    private volatile @Nullable Instant lastErrorAt;

    public WarehouseHealthIndicator(JdbcTemplate jdbc, CircuitBreaker breaker, Clock clock) {
        JdbcTemplate ping = new JdbcTemplate(jdbc.getDataSource());
        ping.setQueryTimeout(1);
        this.jdbc = ping;
        this.breaker = breaker;
        this.clock = clock;
    }

    @Override
    public Health health() {
        boolean pingOk = ping();
        CircuitBreaker.State state = breaker.getState();
        Health.Builder builder = state == CircuitBreaker.State.CLOSED && pingOk ? Health.up() : Health.down();
        builder.withDetail("circuitState", state.name());
        Instant error = lastErrorAt;
        if (error != null) {
            builder.withDetail("lastErrorAt", error.toString());
        }
        return builder.build();
    }

    /** From the circuit and the last ping, without any I/O; before the first ping, as if the ping succeeded. */
    public boolean isUp() {
        return breaker.getState() == CircuitBreaker.State.CLOSED && (checkedAt == null || lastPing);
    }

    private boolean ping() {
        Instant now = clock.instant();
        Instant checked = checkedAt;
        if (checked != null && Duration.between(checked, now).compareTo(CACHE) < 0) {
            return lastPing;
        }
        if (!pinging.compareAndSet(false, true)) {
            return lastPing;
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            lastPing = true;
        } catch (RuntimeException e) {
            lastPing = false;
            lastErrorAt = now;
        } finally {
            checkedAt = now;
            pinging.set(false);
        }
        return lastPing;
    }
}
