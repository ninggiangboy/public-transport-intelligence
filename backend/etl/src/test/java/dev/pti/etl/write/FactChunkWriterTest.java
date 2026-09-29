package dev.pti.etl.write;

import static dev.pti.etl.testing.EtlFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.SalePointRow;
import dev.pti.etl.core.TicketSaleRow;
import dev.pti.etl.core.TripUpdateRow;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.testing.EtlFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** DOC-19 §4.3 without a database: what is written, in which order, and how messages are counted. */
class FactChunkWriterTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-29");

    /** Records every batch; a statement returns 0 for rows whose payload hash is in {@link #blocked}. */
    private final List<String> statements = new ArrayList<>();

    private final List<SqlParameterSource[]> batches = new ArrayList<>();
    private final Set<String> blocked = new HashSet<>();
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(new JdbcTemplate()) {
        @Override
        public int[] batchUpdate(String sql, SqlParameterSource[] params) {
            statements.add(sql);
            batches.add(params);
            return Arrays.stream(params)
                    .mapToInt(p ->
                            p.hasValue("payload_hash") && blocked.contains((String) p.getValue("payload_hash")) ? 0 : 1)
                    .toArray();
        }
    };
    private final DedupRegistry registry = mock(DedupRegistry.class);
    private final DeadLetterWriter deadLetters = mock(DeadLetterWriter.class);
    private final RefundRule refunds = new RefundRule(new JdbcTemplate(), EtlFixtures.dq());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private FactChunkWriter writer(boolean dedup, DqProperties dq) {
        return new FactChunkWriter(
                jdbc, refunds, registry, dedup, deadLetters, new KnownKeyCache(100), new WriteStats(meters), dq);
    }

    private FactChunkWriter writer() {
        return writer(false, EtlFixtures.dq());
    }

    private static WriteContext live() {
        return new WriteContext(UUID.randomUUID(), RunMode.STREAM, false, NOW);
    }

    private static WriteSet vp(String vehicle, Instant event, String hash) {
        VehiclePositionRow row = new VehiclePositionRow(
                DAY,
                vehicle,
                event,
                "T",
                "18",
                (short) 0,
                44.9,
                -93.2,
                null,
                null,
                1,
                "S",
                "STOPPED_AT",
                null,
                (short) 2,
                hash);
        return WriteSet.vehiclePosition(
                EtlFixtures.message(EtlSource.GTFS_RT_VEHICLE_POSITION, "{}"), hash, vehicle + "|" + event, row);
    }

    private static WriteSet tu(String trip, String hash, int... stops) {
        List<TripUpdateRow> rows = Arrays.stream(stops)
                .mapToObj(s -> new TripUpdateRow(
                        DAY,
                        trip,
                        s,
                        "18",
                        (short) 0,
                        "S" + s,
                        "V9",
                        "SCHEDULED",
                        null,
                        NOW,
                        null,
                        0,
                        false,
                        NOW,
                        hash))
                .toList();
        return WriteSet.tripUpdate(
                EtlFixtures.message(EtlSource.GTFS_RT_TRIP_UPDATE, "{}"), hash, DAY + "|" + trip, NOW, rows);
    }

    private static WriteSet sale(String salePoint, String hash) {
        TicketSaleRow row = new TicketSaleRow(
                DAY,
                UUID.randomUUID(),
                salePoint,
                "18",
                null,
                "SINGLE",
                "SALE",
                new BigDecimal("2.50"),
                "USD",
                null,
                "COMPLETED",
                false,
                NOW,
                NOW,
                10,
                NOW,
                hash,
                "c");
        return WriteSet.ticketSale(EtlFixtures.message(EtlSource.TICKETING_SALES, "{}"), hash, hash, row);
    }

    private static WriteSet salePoint(String id) {
        return WriteSet.salePoint(
                EtlFixtures.message(EtlSource.TICKETING_SALE_POINTS, "{}"),
                "sp-" + id,
                id,
                NOW,
                new SalePointRow(id, "Name", "KIOSK", null, null, false, 5));
    }

    private List<Object> column(String statementPrefix, String column) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(statementPrefix)) {
                return Arrays.stream(batches.get(i))
                        .map(p -> p.getValue(column))
                        .toList();
            }
        }
        return List.of();
    }

    private List<String> tables() {
        return statements.stream()
                .map(s -> s.lines()
                        .filter(l -> l.startsWith("INSERT INTO"))
                        .findFirst()
                        .orElseThrow()
                        .split(" ")[2])
                .toList();
    }

    @Test
    void writesPlaceholdersFactsAndLatestInAFixedOrderSortedByKey() {
        WriteOutcome outcome = writer().write(
                        List.of(vp("B", NOW, "h2"), vp("A", NOW.minusSeconds(5), "h1"), vp("A", NOW, "h3")), live());

        assertThat(tables())
                .containsExactly("dw.dim_vehicle", "dw.fact_vehicle_position", "dw.vehicle_position_latest");
        assertThat(column("dim_vehicle", "vehicle_id")).containsExactly("A", "B");
        assertThat(column("fact_vehicle_position", "vehicle_id")).containsExactly("A", "A", "B");
        assertThat(column("vehicle_position_latest", "vehicle_id")).containsExactly("A", "B");
        assertThat(column("vehicle_position_latest", "payload_hash")).containsExactly("h3", "h2");
        assertThat(outcome.writtenCount()).isEqualTo(3);
        assertThat(meters.find("pti.etl.records")
                        .tag("outcome", "written")
                        .counter()
                        .count())
                .isEqualTo(3);
    }

    @Test
    void knownVehiclesAreNotInsertedAgain() {
        FactChunkWriter writer = writer();
        writer.write(List.of(vp("A", NOW, "h1")), live());
        statements.clear();
        writer.write(List.of(vp("A", NOW.plusSeconds(5), "h2")), live());
        assertThat(tables()).doesNotContain("dw.dim_vehicle");
    }

    @Test
    void aMessageWhoseRowsAreAllBlockedIsAGuardDuplicate() {
        blocked.add("h1");
        WriteOutcome outcome = writer().write(List.of(tu("T1", "h1", 1, 2), tu("T2", "h2", 1)), live());
        assertThat(outcome.writtenCount()).isEqualTo(1);
        assertThat(outcome.duplicateGuard()).isEqualTo(1);
        assertThat(column("fact_trip_update", "trip_id")).containsExactly("T1", "T1", "T2");
    }

    @Test
    void theRegistryDropsSeenMessagesButNotOnReplay() {
        when(registry.registerNew(eq(EtlSource.GTFS_RT_VEHICLE_POSITION), anyCollection(), any()))
                .thenReturn(Set.of("h1"));
        FactChunkWriter writer = writer(true, EtlFixtures.dq());

        WriteOutcome outcome = writer.write(List.of(vp("A", NOW, "h1"), vp("B", NOW, "h2")), live());
        assertThat(outcome.writtenCount()).isEqualTo(1);
        assertThat(outcome.duplicateRegistry()).isEqualTo(1);

        WriteOutcome replay = writer.write(
                List.of(vp("B", NOW, "h2")), new WriteContext(UUID.randomUUID(), RunMode.BATCH, true, NOW));
        assertThat(replay.writtenCount()).isEqualTo(1);
    }

    @Test
    void salePointsComeBeforeSalesAndOnlyUnknownOnesGetAPlaceholder() {
        writer().write(List.of(sale("KIOSK-2", "s1"), salePoint("KIOSK-1"), sale("KIOSK-1", "s2")), live());

        assertThat(tables()).containsExactly("dw.dim_sale_point", "dw.dim_sale_point", "dw.fact_ticket_sales");
        assertThat(column("'CDC'", "sale_point_id")).containsExactly("KIOSK-1");
        assertThat(column("'INFERRED'", "sale_point_id")).containsExactly("KIOSK-2");
    }

    @Test
    void chunkRuleRejectionsGoToTheDeadLetterQueue() {
        WriteSet earlier = vp("A", NOW, "h1");
        WriteSet later = vp("A", NOW, "h2");
        WriteOutcome outcome = writer().write(List.of(earlier, later), live());
        assertThat(outcome.rejected()).isEqualTo(1);
        verify(deadLetters).write(any(DeadLetter.class));

        writer().write(List.of(earlier, later), new WriteContext(UUID.randomUUID(), RunMode.BATCH, true, NOW));
        verify(deadLetters).writeReplay(any(DeadLetter.class));
    }

    @Test
    void withDq02OffIdenticalMessagesStillWriteOnce() {
        when(registry.registerNew(any(), anyCollection(), any())).thenReturn(Set.of("h1"));
        DqProperties dq = EtlFixtures.dq(Map.of("DQ-02", new DqProperties.Rule(false)));
        WriteOutcome outcome = writer(true, dq).write(List.of(vp("A", NOW, "h1"), vp("A", NOW, "h1")), live());
        assertThat(outcome.writtenCount()).isEqualTo(1);
        assertThat(outcome.duplicateRegistry()).isEqualTo(1);
        verify(deadLetters, never()).write(any());
    }

    @Test
    void nothingToWrite() {
        WriteSet empty = WriteSet.empty(EtlFixtures.tombstone(EtlSource.TICKETING_SALES));
        assertThat(writer().write(List.of(empty), live())).isEqualTo(WriteOutcome.none());
        assertThat(statements).isEmpty();
    }

    @Test
    void theOutcomeSummarisesWhatWasWritten() {
        WriteOutcome outcome = writer().write(List.of(vp("A", NOW.minusSeconds(9), "h1"), tu("T", "h2", 1)), live());
        assertThat(outcome.minEventTimestamp()).contains(NOW.minusSeconds(9));
        assertThat(outcome.maxEventTimestamp()).contains(NOW);
        assertThat(outcome.routeIds()).containsExactly("18");
        WriteOutcome sum = outcome.plus(new WriteOutcome(List.of(), 1, 2, 3, 4));
        assertThat(sum.duplicate()).isEqualTo(6);
        assertThat(sum.rejected()).isEqualTo(4);
        assertThat(sum.written().stream().map(WriteSet::businessKey).collect(Collectors.toSet()))
                .hasSize(2);
    }
}
