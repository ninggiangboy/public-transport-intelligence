package dev.pti.simulator.ledger;

import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes {@code sim.sim_ledger} (DOC-25 §6.4, DR-28). Acknowledged messages go into a bounded queue; the
 * {@code sim-ledger} thread inserts them in batches of up to {@code batch-size} rows or every
 * {@code flush-interval}, autocommit. A full queue blocks the producer callback, which in turn blocks
 * {@code send} and makes the emitter skip emissions: an acknowledged row is never dropped. A failed flush is retried
 * with a backoff from 200 ms to 5 s, without limit. Partitions are maintained at startup and every hour, by real UTC
 * date, because {@code produced_at} is real time.
 */
public final class LedgerWriter implements Ledger, SmartLifecycle {

    /** Starts before the Kafka sink and the loops, stops after them, so it drains everything they produced. */
    public static final int PHASE = 100;

    private static final Logger log = LoggerFactory.getLogger(LedgerWriter.class);

    private static final String INSERT = """
            INSERT INTO sim.sim_ledger
              (produced_at, message_id, entity_type, kafka_topic, kafka_partition, kafka_offset, business_keys,
               event_timestamp, schema_version, payload_hash, intended_invalid, invalid_kind, is_resend, resend_of,
               scenario_run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final long MIN_BACKOFF_MILLIS = 200;
    private static final long MAX_BACKOFF_MILLIS = 5_000;
    private static final long DRAIN_TIMEOUT_MILLIS = 20_000;
    private static final Duration MAINTENANCE_EVERY = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final BusinessClock clock;
    private final BlockingQueue<Row> queue;
    private final int batchSize;
    private final long flushIntervalMillis;
    private final Duration retention;
    private final Counter rows;
    private volatile @Nullable Instant lastFlushAt;
    private volatile boolean running;
    private volatile boolean stopping;
    private @Nullable Thread thread;
    private @Nullable Instant nextMaintenance;

    public LedgerWriter(
            JdbcTemplate jdbc,
            BusinessClock clock,
            int queueCapacity,
            int batchSize,
            Duration flushInterval,
            Duration retention,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
        this.flushIntervalMillis = flushInterval.toMillis();
        this.retention = retention;
        this.rows = Counter.builder("pti.sim.ledger.rows").register(registry);
        Gauge.builder("pti.sim.ledger.queue.depth", queue, BlockingQueue::size).register(registry);
    }

    @Override
    public void record(LedgerEntry entry, String topic, int partition, long offset) {
        try {
            queue.put(new Row(entry, topic, partition, offset));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while queueing a ledger row", e);
        }
    }

    @Override
    public int queueDepth() {
        return queue.size();
    }

    @Override
    public @Nullable Instant lastFlushAt() {
        return lastFlushAt;
    }

    @Override
    public synchronized void start() {
        stopping = false;
        running = true;
        Thread t = Thread.ofPlatform().name("sim-ledger").unstarted(this::run);
        thread = t;
        t.start();
    }

    /** Stops taking new work and writes what is queued, for at most 20 s (DOC-25 §10). */
    @Override
    public synchronized void stop() {
        Thread t = thread;
        if (t == null) {
            return;
        }
        stopping = true;
        try {
            t.join(DRAIN_TIMEOUT_MILLIS);
            if (t.isAlive()) {
                log.warn("Ledger not drained within {} ms: {} rows left", DRAIN_TIMEOUT_MILLIS, queue.size());
                t.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void run() {
        List<Row> batch = new ArrayList<>(batchSize);
        while (!(stopping && queue.isEmpty())) {
            try {
                maintainPartitions();
                collect(batch);
                if (!batch.isEmpty()) {
                    flushWithRetry(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Waits for the first row, then gathers more until the batch is full or the flush interval has passed. */
    private void collect(List<Row> batch) throws InterruptedException {
        Row first = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
        if (first == null) {
            return;
        }
        batch.add(first);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushIntervalMillis);
        while (batch.size() < batchSize) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            Row next = queue.poll(left, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batch.add(next);
            queue.drainTo(batch, batchSize - batch.size());
        }
    }

    private void flushWithRetry(List<Row> batch) throws InterruptedException {
        long backoff = MIN_BACKOFF_MILLIS;
        while (true) {
            try {
                insert(batch);
                rows.increment(batch.size());
                lastFlushAt = clock.realNow();
                return;
            } catch (DataAccessException e) {
                log.warn("Ledger flush of {} rows failed, retrying in {} ms: {}", batch.size(), backoff, e.toString());
                TimeUnit.MILLISECONDS.sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    private void insert(List<Row> batch) {
        jdbc.batchUpdate(INSERT, batch, batch.size(), LedgerWriter::bind);
    }

    private static void bind(PreparedStatement ps, Row row) throws SQLException {
        LedgerEntry e = row.entry();
        Array keys = ps.getConnection().createArrayOf("text", e.businessKeys().toArray());
        ps.setObject(1, OffsetDateTime.ofInstant(e.producedAt(), ZoneOffset.UTC));
        ps.setObject(2, e.messageId());
        ps.setString(3, e.entityType());
        ps.setString(4, row.topic());
        ps.setInt(5, row.partition());
        ps.setLong(6, row.offset());
        ps.setArray(7, keys);
        ps.setObject(8, OffsetDateTime.ofInstant(e.eventTimestamp(), ZoneOffset.UTC));
        ps.setShort(9, (short) e.schemaVersion());
        ps.setString(10, e.payloadHash());
        ps.setBoolean(11, e.invalidKind() != null);
        ps.setString(12, e.invalidKind());
        ps.setBoolean(13, e.resendOf() != null);
        setUuid(ps, 14, e.resendOf());
        setUuid(ps, 15, e.scenarioRunId());
    }

    private static void setUuid(PreparedStatement ps, int index, @Nullable Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, value);
        }
    }

    /** {@code ensure(today − 1, today + 2)} and {@code drop_before(today − retention)}, UTC real date. */
    private void maintainPartitions() {
        Instant now = clock.realNow();
        if (nextMaintenance != null && now.isBefore(nextMaintenance)) {
            return;
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        try {
            Integer created = jdbc.queryForObject(
                    "SELECT sim.ensure_ledger_partitions(?, ?)", Integer.class, today.minusDays(1), today.plusDays(2));
            Integer dropped = jdbc.queryForObject(
                    "SELECT sim.drop_ledger_partitions_before(?)",
                    Integer.class,
                    today.minusDays(Math.max(1, retention.toDays())));
            log.info("Ledger partitions maintained: created={} dropped={}", created, dropped);
            nextMaintenance = now.plus(MAINTENANCE_EVERY);
        } catch (DataAccessException e) {
            log.warn("Ledger partition maintenance failed, retrying in a minute: {}", e.toString());
            nextMaintenance = now.plus(Duration.ofMinutes(1));
        }
    }

    private record Row(LedgerEntry entry, String topic, int partition, long offset) {}
}
