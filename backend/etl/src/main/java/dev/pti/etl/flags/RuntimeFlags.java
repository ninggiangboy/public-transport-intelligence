package dev.pti.etl.flags;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The on/off switches of {@code ops.runtime_flag} (DR-19), read by every pod every few seconds (DOC-19 §2.1). A
 * change is published as {@link RuntimeFlagChanged}; a failed read keeps the last known values.
 */
public class RuntimeFlags {

    private static final Logger log = LoggerFactory.getLogger(RuntimeFlags.class);

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Map<String, Boolean> values = new ConcurrentHashMap<>();

    public RuntimeFlags(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public boolean isEnabled(String key) {
        return values.getOrDefault(key, false);
    }

    /** Only boolean flags are read; other values are ignored here. */
    public synchronized void refresh() {
        Map<String, Boolean> read = new HashMap<>();
        try {
            jdbc.query("SELECT key, value::text AS value FROM ops.runtime_flag", rs -> {
                String value = rs.getString("value").trim();
                if (value.equals("true") || value.equals("false")) {
                    read.put(rs.getString("key"), Boolean.parseBoolean(value));
                }
            });
        } catch (RuntimeException e) {
            log.warn("Cannot read runtime flags, keeping the last values: {}", e.toString());
            return;
        }
        read.forEach((key, enabled) -> {
            Boolean previous = values.put(key, enabled);
            if (!enabled.equals(previous)) {
                if (previous != null) {
                    log.info("Runtime flag {} is now {}", key, enabled);
                }
                events.publishEvent(new RuntimeFlagChanged(key, enabled));
            }
        });
    }
}
