package dev.pti.etl.write;

import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.SalePointRow;
import dev.pti.etl.core.TicketSaleRow;
import dev.pti.etl.core.TripUpdateRow;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.core.WriteSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The upsert writer shared by streaming and batch mode (DOC-19 §4.3). Runs inside the caller's transaction:
 *
 * <ol>
 *   <li>chunk rules DQ-02, DQ-12 and DQ-13; rejected messages go to the dead-letter queue in the same transaction;
 *   <li>the dedup registry, except on replay (DR-16);
 *   <li>one {@code batchUpdate} per target table, in a fixed table order and sorted by primary key so that two
 *       concurrent chunks lock rows in the same order: {@code dim_vehicle} placeholders, {@code dim_sale_point} (CDC
 *       rows, then INFERRED placeholders), the facts, {@code vehicle_position_latest}.
 * </ol>
 *
 * <p>An update count of 0 means the upsert guard blocked the row (DOC-14 §8); a message all of whose rows were
 * blocked counts as a duplicate. The JDBC URL must not set {@code reWriteBatchedInserts}, which would hide the
 * per-row counts.
 */
public final class FactChunkWriter implements ChunkWriter {

    private static final String UPSERT_VP = SqlResource.load("upsert_vehicle_position");
    private static final String UPSERT_TU = SqlResource.load("upsert_trip_update");
    private static final String UPSERT_TX = SqlResource.load("upsert_ticket_sales");
    private static final String UPSERT_LATEST = SqlResource.load("upsert_vehicle_position_latest");
    private static final String UPSERT_SALE_POINT = SqlResource.load("upsert_dim_sale_point");
    private static final String INSERT_SALE_POINT_INFERRED = SqlResource.load("insert_dim_sale_point_inferred");
    private static final String INSERT_VEHICLE = SqlResource.load("insert_dim_vehicle_realtime");

    private final NamedParameterJdbcTemplate jdbc;
    private final DuplicateKeyRule duplicateKeyRule;
    private final RefundRule refundRule;
    private final DedupRegistry dedupRegistry;
    private final boolean dedupEnabled;
    private final DeadLetterWriter deadLetters;
    private final KnownKeyCache knownKeys;
    private final WriteStats stats;
    private final DqProperties dq;

    public FactChunkWriter(
            NamedParameterJdbcTemplate jdbc,
            RefundRule refundRule,
            DedupRegistry dedupRegistry,
            boolean dedupEnabled,
            DeadLetterWriter deadLetters,
            KnownKeyCache knownKeys,
            WriteStats stats,
            DqProperties dq) {
        this.jdbc = jdbc;
        this.duplicateKeyRule = new DuplicateKeyRule();
        this.refundRule = refundRule;
        this.dedupRegistry = dedupRegistry;
        this.dedupEnabled = dedupEnabled;
        this.deadLetters = deadLetters;
        this.knownKeys = knownKeys;
        this.stats = stats;
        this.dq = dq;
    }

    @Override
    public WriteOutcome write(List<WriteSet> items, WriteContext context) {
        List<WriteSet> nonEmpty = items.stream().filter(i -> !i.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            return WriteOutcome.none();
        }

        // 1. Chunk rules.
        ChunkRuleResult dedup =
                dq.enabled(DuplicateKeyRule.ID) ? duplicateKeyRule.apply(nonEmpty) : ChunkRuleResult.keepAll(nonEmpty);
        ChunkRuleResult refunds = refundRule.apply(dedup.kept(), context);
        List<ChunkRuleResult.Rejected> rejected = new ArrayList<>(dedup.rejected());
        rejected.addAll(refunds.rejected());
        for (ChunkRuleResult.Rejected r : rejected) {
            DeadLetter letter =
                    DeadLetter.of(r.item().origin(), r.violation(), r.item().businessKey(), context.batchId());
            if (context.replay()) {
                deadLetters.writeReplay(letter);
            } else {
                deadLetters.write(letter);
            }
        }

        // 2. Dedup registry.
        List<WriteSet> toWrite = refunds.kept();
        int registryDuplicates = 0;
        if (!context.replay() && dedupEnabled && !toWrite.isEmpty()) {
            List<WriteSet> fresh = new ArrayList<>();
            Map<EtlSource, List<WriteSet>> bySource = toWrite.stream()
                    .collect(Collectors.groupingBy(WriteSet::source, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<EtlSource, List<WriteSet>> e : bySource.entrySet()) {
                Set<String> newHashes = dedupRegistry.registerNew(
                        e.getKey(),
                        e.getValue().stream().map(WriteSet::messageHash).toList(),
                        context.batchId());
                Set<String> taken = new HashSet<>();
                for (WriteSet w : e.getValue()) {
                    // Two messages of one chunk with one hash survive DQ-02 only when it is off; keep the first.
                    if (newHashes.contains(w.messageHash()) && taken.add(w.messageHash())) {
                        fresh.add(w);
                    } else {
                        registryDuplicates++;
                    }
                }
            }
            toWrite = fresh;
        }

        // 3. Writes, in a fixed table order.
        boolean[] changed = new boolean[toWrite.size()];
        writeVehiclePlaceholders(toWrite);
        Set<String> salePointIds = writeSalePoints(toWrite, context, changed);
        writeRows(UPSERT_VP, toWrite, WriteSet::vehiclePositions, VP_ORDER, r -> vehiclePosition(r, context), changed);
        writeRows(UPSERT_TU, toWrite, WriteSet::tripUpdates, TU_ORDER, r -> tripUpdate(r, context), changed);
        writeRows(UPSERT_TX, toWrite, WriteSet::ticketSales, TX_ORDER, r -> ticketSale(r, context), changed);
        writeLatest(toWrite, context);

        List<WriteSet> written = new ArrayList<>();
        int guardDuplicates = 0;
        for (int i = 0; i < toWrite.size(); i++) {
            if (changed[i]) {
                written.add(toWrite.get(i));
            } else {
                guardDuplicates++;
            }
        }

        knownKeys.addVehiclesAfterCommit(
                toWrite.stream().flatMap(w -> w.vehicleIds().stream()).collect(Collectors.toSet()));
        knownKeys.addSalePointsAfterCommit(salePointIds);

        WriteOutcome outcome =
                new WriteOutcome(written, dedup.collapsed(), registryDuplicates, guardDuplicates, rejected.size());
        stats.recordWrite(nonEmpty.getFirst().source(), context.mode(), outcome);
        return outcome;
    }

    private void writeVehiclePlaceholders(List<WriteSet> items) {
        List<String> unknown = knownKeys.unknownVehicles(
                items.stream().flatMap(w -> w.vehicleIds().stream()).toList());
        if (!unknown.isEmpty()) {
            jdbc.batchUpdate(
                    INSERT_VEHICLE,
                    unknown.stream()
                            .map(id -> new MapSqlParameterSource("vehicle_id", id))
                            .toArray(MapSqlParameterSource[]::new));
        }
    }

    /** CDC rows first, then INFERRED placeholders for sales whose sale point is not known yet (DOC-14 §8.5). */
    private Set<String> writeSalePoints(List<WriteSet> items, WriteContext context, boolean[] changed) {
        writeRows(
                UPSERT_SALE_POINT,
                items,
                WriteSet::salePoints,
                Comparator.comparing(SalePointRow::salePointId),
                r -> salePoint(r, context),
                changed);
        Set<String> ids = new LinkedHashSet<>();
        items.forEach(w -> w.salePoints().forEach(r -> ids.add(r.salePointId())));
        List<String> referenced = items.stream()
                .flatMap(w -> w.ticketSales().stream())
                .map(TicketSaleRow::salePointId)
                .filter(id -> !ids.contains(id))
                .toList();
        List<String> unknown = knownKeys.unknownSalePoints(referenced);
        if (!unknown.isEmpty()) {
            jdbc.batchUpdate(
                    INSERT_SALE_POINT_INFERRED,
                    unknown.stream()
                            .map(id -> new MapSqlParameterSource()
                                    .addValue("sale_point_id", id)
                                    .addValue("batch_id", context.batchId()))
                            .toArray(MapSqlParameterSource[]::new));
        }
        ids.addAll(referenced);
        return ids;
    }

    /** Newest position per vehicle; no {@code :replay}, so replayed history never moves a bus back (DOC-14 §8.4). */
    private void writeLatest(List<WriteSet> items, WriteContext context) {
        Map<String, VehiclePositionRow> newest = new HashMap<>();
        for (WriteSet w : items) {
            for (VehiclePositionRow r : w.vehiclePositions()) {
                newest.merge(r.vehicleId(), r, (a, b) -> b.eventTimestamp().isAfter(a.eventTimestamp()) ? b : a);
            }
        }
        if (newest.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] params = newest.values().stream()
                .sorted(Comparator.comparing(VehiclePositionRow::vehicleId))
                .map(r -> vehiclePosition(r, context))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_LATEST, params);
    }

    private <R> void writeRows(
            String sql,
            List<WriteSet> items,
            Function<WriteSet, List<R>> rows,
            Comparator<R> order,
            Function<R, MapSqlParameterSource> params,
            boolean[] changed) {
        record Owned<R>(R row, int owner) {}
        List<Owned<R>> all = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            for (R r : rows.apply(items.get(i))) {
                all.add(new Owned<>(r, i));
            }
        }
        if (all.isEmpty()) {
            return;
        }
        all.sort(Comparator.comparing(Owned::row, order));
        MapSqlParameterSource[] batch =
                all.stream().map(o -> params.apply(o.row())).toArray(MapSqlParameterSource[]::new);
        int[] counts = jdbc.batchUpdate(sql, batch);
        for (int i = 0; i < counts.length; i++) {
            // Anything but an explicit 0 (e.g. SUCCESS_NO_INFO) counts as a write.
            if (counts[i] != 0) {
                changed[all.get(i).owner()] = true;
            }
        }
    }

    private static final Comparator<VehiclePositionRow> VP_ORDER = Comparator.comparing(VehiclePositionRow::serviceDate)
            .thenComparing(VehiclePositionRow::vehicleId)
            .thenComparing(VehiclePositionRow::eventTimestamp);

    private static final Comparator<TripUpdateRow> TU_ORDER = Comparator.comparing(TripUpdateRow::serviceDate)
            .thenComparing(TripUpdateRow::tripId)
            .thenComparingInt(TripUpdateRow::stopSequence);

    private static final Comparator<TicketSaleRow> TX_ORDER =
            Comparator.comparing(TicketSaleRow::saleDate).thenComparing(TicketSaleRow::transactionId);

    static MapSqlParameterSource vehiclePosition(VehiclePositionRow r, WriteContext context) {
        return SqlParams.of()
                .date("service_date", r.serviceDate())
                .text("vehicle_id", r.vehicleId())
                .timestamp("event_timestamp", r.eventTimestamp())
                .text("trip_id", r.tripId())
                .text("route_id", r.routeId())
                .smallint("direction_id", r.directionId())
                .real8("lat", r.lat())
                .real8("lon", r.lon())
                .real4("bearing", r.bearing())
                .real4("speed_mps", r.speedMps())
                .integer("current_stop_sequence", r.currentStopSequence())
                .text("stop_id", r.stopId())
                .text("current_status", r.currentStatus())
                .text("occupancy_status", r.occupancyStatus())
                .smallint("schema_version", r.schemaVersion())
                .text("payload_hash", r.payloadHash())
                .uuid("batch_id", context.batchId())
                .bool("replay", context.replay())
                .build();
    }

    static MapSqlParameterSource tripUpdate(TripUpdateRow r, WriteContext context) {
        return SqlParams.of()
                .date("service_date", r.serviceDate())
                .text("trip_id", r.tripId())
                .integer("stop_sequence", r.stopSequence())
                .text("route_id", r.routeId())
                .smallint("direction_id", r.directionId())
                .text("stop_id", r.stopId())
                .text("vehicle_id", r.vehicleId())
                .text("schedule_relationship", r.scheduleRelationship())
                .timestamp("scheduled_arrival", r.scheduledArrival())
                .timestamp("arrival_time", r.arrivalTime())
                .timestamp("departure_time", r.departureTime())
                .integer("delay_seconds", r.delaySeconds())
                .bool("is_observed", r.observed())
                .timestamp("event_timestamp", r.eventTimestamp())
                .text("payload_hash", r.payloadHash())
                .uuid("batch_id", context.batchId())
                .bool("replay", context.replay())
                .build();
    }

    static MapSqlParameterSource ticketSale(TicketSaleRow r, WriteContext context) {
        return SqlParams.of()
                .date("sale_date", r.saleDate())
                .uuid("transaction_id", r.transactionId())
                .text("sale_point_id", r.salePointId())
                .text("route_id", r.routeId())
                .text("stop_id", r.stopId())
                .text("ticket_type", r.ticketType())
                .text("txn_type", r.txnType())
                .numeric("amount", r.amount())
                .text("currency", r.currency())
                .uuid("refund_of", r.refundOf())
                .text("status", r.status())
                .bool("is_deleted", r.deleted())
                .timestamp("created_at", r.createdAt())
                .timestamp("source_updated_at", r.sourceUpdatedAt())
                .bigint("source_lsn", r.sourceLsn())
                .timestamp("event_timestamp", r.eventTimestamp())
                .text("payload_hash", r.payloadHash())
                .uuid("batch_id", context.batchId())
                .bool("replay", context.replay())
                .build();
    }

    static MapSqlParameterSource salePoint(SalePointRow r, WriteContext context) {
        return SqlParams.of()
                .text("sale_point_id", r.salePointId())
                .text("name", r.name())
                .text("kind", r.kind())
                .text("stop_id", r.stopId())
                .text("route_id", r.routeId())
                .bool("is_deleted", r.deleted())
                .bigint("source_lsn", r.sourceLsn())
                .uuid("batch_id", context.batchId())
                .bool("replay", context.replay())
                .build();
    }
}
