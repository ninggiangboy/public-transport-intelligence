package dev.pti.etl.core;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Everything one message writes, grouped per target table (DOC-19 §4.1). It is the unit of skip and scan: a
 * TripUpdate with twelve stops writes twelve rows or none.
 *
 * @param messageHash payload hash (DOC-09 §9), the key of {@code ops.dedup_registry}
 * @param businessKey the dead-letter and log form of the key (DOC-22 §1.2)
 * @param version what orders two messages with the same key: event time in epoch milliseconds for GTFS-realtime,
 *     the source LSN for CDC
 */
public record WriteSet(
        InboundMessage origin,
        String messageHash,
        String businessKey,
        Instant eventTimestamp,
        long version,
        List<VehiclePositionRow> vehiclePositions,
        List<TripUpdateRow> tripUpdates,
        List<TicketSaleRow> ticketSales,
        List<SalePointRow> salePoints,
        List<String> vehicleIds) {

    public WriteSet {
        vehiclePositions = List.copyOf(vehiclePositions);
        tripUpdates = List.copyOf(tripUpdates);
        ticketSales = List.copyOf(ticketSales);
        salePoints = List.copyOf(salePoints);
        vehicleIds = List.copyOf(vehicleIds);
    }

    public EtlSource source() {
        return origin.source();
    }

    /** A message that writes nothing, e.g. a tombstone. */
    public static WriteSet empty(InboundMessage origin) {
        return new WriteSet(origin, "", "", Instant.EPOCH, 0, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return vehiclePositions.isEmpty() && tripUpdates.isEmpty() && ticketSales.isEmpty() && salePoints.isEmpty();
    }

    public static WriteSet vehiclePosition(
            InboundMessage origin, String hash, String businessKey, VehiclePositionRow row) {
        return new WriteSet(
                origin,
                hash,
                businessKey,
                row.eventTimestamp(),
                row.eventTimestamp().toEpochMilli(),
                List.of(row),
                List.of(),
                List.of(),
                List.of(),
                List.of(row.vehicleId()));
    }

    public static WriteSet tripUpdate(
            InboundMessage origin, String hash, String businessKey, Instant event, List<TripUpdateRow> rows) {
        List<String> vehicles =
                rows.isEmpty() ? List.of() : List.of(rows.getFirst().vehicleId());
        return new WriteSet(
                origin,
                hash,
                businessKey,
                event,
                event.toEpochMilli(),
                List.of(),
                rows,
                List.of(),
                List.of(),
                vehicles);
    }

    public static WriteSet ticketSale(InboundMessage origin, String hash, String businessKey, TicketSaleRow row) {
        return new WriteSet(
                origin,
                hash,
                businessKey,
                row.eventTimestamp(),
                row.sourceLsn(),
                List.of(),
                List.of(),
                List.of(row),
                List.of(),
                List.of());
    }

    public static WriteSet salePoint(
            InboundMessage origin, String hash, String businessKey, Instant event, SalePointRow row) {
        return new WriteSet(
                origin,
                hash,
                businessKey,
                event,
                row.sourceLsn(),
                List.of(),
                List.of(),
                List.of(),
                List.of(row),
                List.of());
    }

    /** The same message restricted to some of its stops, after DQ-02 dropped the superseded ones. */
    public WriteSet withTripUpdates(List<TripUpdateRow> rows) {
        return new WriteSet(
                origin,
                messageHash,
                businessKey,
                eventTimestamp,
                version,
                vehiclePositions,
                rows,
                ticketSales,
                salePoints,
                vehicleIds);
    }

    /** Offset in the source partition, for "the later one wins" among equal versions (DOC-16 §2.1). */
    public long offsetOrZero() {
        Long offset = origin.offset();
        return offset == null ? 0 : offset;
    }

    public @Nullable String kafkaPosition() {
        return origin.topic() == null ? null : origin.topic() + "-" + origin.partition() + "@" + origin.offset();
    }
}
