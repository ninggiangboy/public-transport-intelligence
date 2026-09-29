package dev.pti.etl;

import com.github.f4b6a3.uuid.UuidCreator;
import com.zaxxer.hikari.HikariDataSource;
import dev.pti.common.pii.PiiScrubber;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.SalePointRow;
import dev.pti.etl.core.TicketSaleRow;
import dev.pti.etl.core.TripUpdateRow;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.testing.EtlFixtures;
import dev.pti.etl.write.DedupRegistry;
import dev.pti.etl.write.FactChunkWriter;
import dev.pti.etl.write.JdbcDeadLetterWriter;
import dev.pti.etl.write.KnownKeyCache;
import dev.pti.etl.write.RefundRule;
import dev.pti.etl.write.WriteStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The migrated warehouse as {@code etl_writer}, and builders of rows and messages for the write-path tests. */
public final class WarehouseSupport {

    public static final LocalDate SERVICE_DATE = LocalDate.parse("2026-09-29");
    public static final Instant NOW = Instant.parse("2026-09-29T21:20:00Z");

    private static final AtomicLong OFFSETS = new AtomicLong(1_000);

    public final HikariDataSource dataSource;
    public final JdbcTemplate jdbc;
    public final NamedParameterJdbcTemplate named;
    public final TransactionTemplate tx;
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final JdbcDeadLetterWriter deadLetters;

    public WarehouseSupport() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(MigratedDatabases.jdbcUrl("pti_warehouse"));
        dataSource.setUsername("etl_writer");
        dataSource.setPassword(MigratedDatabases.password("etl_writer"));
        dataSource.setMaximumPoolSize(4);
        dataSource.addDataSourceProperty("prepareThreshold", "0");
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        deadLetters = new JdbcDeadLetterWriter(named, PiiScrubber.withDefaults(), 1_048_576, meters);
    }

    public void close() {
        dataSource.close();
    }

    public static DqProperties dq() {
        return EtlFixtures.dq();
    }

    public FactChunkWriter writer(boolean dedup) {
        return new FactChunkWriter(
                named,
                new RefundRule(jdbc, dq()),
                new DedupRegistry(jdbc),
                dedup,
                deadLetters,
                new KnownKeyCache(1_000),
                new WriteStats(meters),
                dq());
    }

    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A 64-character hex hash, the shape of {@code payload_hash}. */
    public static String hash(String seed) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static UUID batchId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    public static InboundMessage message(EtlSource source, String key, String value) {
        return new InboundMessage(
                source,
                key,
                value.getBytes(StandardCharsets.UTF_8),
                source.requireTopic(),
                0,
                OFFSETS.incrementAndGet(),
                NOW.minusSeconds(2),
                Map.of());
    }

    public static VehiclePositionRow vp(String vehicle, Instant event, double lat, String hash) {
        return new VehiclePositionRow(
                SERVICE_DATE,
                vehicle,
                event,
                "T1",
                "18",
                (short) 0,
                lat,
                -93.27,
                90f,
                8.5f,
                3,
                "S1",
                "IN_TRANSIT_TO",
                null,
                (short) 2,
                hash);
    }

    public static WriteSet vpSet(VehiclePositionRow row) {
        return WriteSet.vehiclePosition(
                message(EtlSource.GTFS_RT_VEHICLE_POSITION, row.vehicleId(), "{\"lat\":" + row.lat() + "}"),
                row.payloadHash(),
                row.vehicleId() + "|" + row.eventTimestamp(),
                row);
    }

    public static TripUpdateRow tu(String trip, int seq, Instant event, boolean observed, int delay, String hash) {
        Instant scheduled = NOW.minusSeconds(600).plusSeconds(seq * 120L);
        return new TripUpdateRow(
                SERVICE_DATE,
                trip,
                seq,
                "18",
                (short) 0,
                "S" + seq,
                "V1",
                "SCHEDULED",
                scheduled,
                scheduled.plusSeconds(delay),
                null,
                delay,
                observed,
                event,
                hash);
    }

    public static WriteSet tuSet(Instant event, String hash, TripUpdateRow... rows) {
        String trip = rows[0].tripId();
        return WriteSet.tripUpdate(
                message(EtlSource.GTFS_RT_TRIP_UPDATE, trip, "{\"trip\":\"" + trip + "\"}"),
                hash,
                SERVICE_DATE + "|" + trip,
                event,
                List.of(rows));
    }

    public static TicketSaleRow sale(
            UUID id,
            String salePoint,
            String txnType,
            String amount,
            UUID refundOf,
            long lsn,
            boolean deleted,
            Instant createdAt,
            String op,
            String hash) {
        return new TicketSaleRow(
                SERVICE_DATE,
                id,
                salePoint,
                null,
                null,
                "SINGLE",
                txnType,
                new BigDecimal(amount),
                "USD",
                refundOf,
                "COMPLETED",
                deleted,
                createdAt,
                createdAt,
                lsn,
                createdAt,
                hash,
                op);
    }

    public static WriteSet txSet(TicketSaleRow row) {
        return WriteSet.ticketSale(
                message(
                        EtlSource.TICKETING_SALES,
                        row.transactionId().toString(),
                        "{\"amount\":\"" + row.amount() + "\"}"),
                row.payloadHash(),
                row.saleDate() + "|" + row.transactionId(),
                row);
    }

    public static WriteSet spSet(String id, long lsn, String name) {
        SalePointRow row = new SalePointRow(id, name, "KIOSK", "S1", null, false, lsn);
        return WriteSet.salePoint(
                message(EtlSource.TICKETING_SALE_POINTS, id, "{\"name\":\"" + name + "\"}"),
                hash(id + "-" + lsn + "-" + name),
                id,
                NOW,
                row);
    }
}
