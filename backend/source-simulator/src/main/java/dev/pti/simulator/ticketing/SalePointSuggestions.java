package dev.pti.simulator.ticketing;

import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The sale points the scenario catalog suggests (DOC-25 §8): up to 20, busiest over the last 7 days of business
 * time first, then the catalog's own order to fill up. Cached for five minutes; the catalog order alone when the
 * ticketing database cannot be read.
 */
public final class SalePointSuggestions implements Supplier<List<String>> {

    private static final Logger log = LoggerFactory.getLogger(SalePointSuggestions.class);

    static final int LIMIT = 20;

    private static final Duration WINDOW = Duration.ofDays(7);

    private static final Duration CACHE = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final SalePointCatalog catalog;
    private final BusinessClock clock;
    private volatile @Nullable Cached cached;

    public SalePointSuggestions(JdbcTemplate jdbc, SalePointCatalog catalog, BusinessClock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public List<String> get() {
        Instant now = clock.realNow();
        Cached c = cached;
        if (c != null && now.isBefore(c.until())) {
            return c.ids();
        }
        Set<String> ids = new LinkedHashSet<>();
        try {
            ids.addAll(jdbc.queryForList(
                    """
                    SELECT sale_point_id FROM public.ticket_transaction
                    WHERE created_at >= ?
                    GROUP BY sale_point_id
                    ORDER BY count(*) DESC, sale_point_id
                    LIMIT ?
                    """, String.class, OffsetDateTime.ofInstant(clock.instant().minus(WINDOW), ZoneOffset.UTC), LIMIT));
        } catch (DataAccessException e) {
            log.warn("Cannot read the busiest sale points, suggesting the catalog order: {}", e.toString());
        }
        for (SalePoint point : catalog.all()) {
            if (ids.size() >= LIMIT) {
                break;
            }
            ids.add(point.id());
        }
        List<String> result = List.copyOf(ids);
        cached = new Cached(result, now.plus(CACHE));
        return result;
    }

    private record Cached(List<String> ids, Instant until) {}
}
