package dev.pti.etl.write;

import static dev.pti.etl.testing.EtlFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.dq.DlqStage;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.TripUpdateRow;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.testing.EtlFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DQ-02, DOC-16 §2.1 and cases 5 to 7 of DOC-16 §8. */
class DuplicateKeyRuleTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-29");
    private final DuplicateKeyRule rule = new DuplicateKeyRule();

    private static WriteSet vp(String vehicle, Instant event, double lat, String hash) {
        VehiclePositionRow row = new VehiclePositionRow(
                DAY,
                vehicle,
                event,
                "T",
                "18",
                (short) 0,
                lat,
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

    private static TripUpdateRow stop(int seq, Instant event, String hash) {
        return new TripUpdateRow(
                DAY, "T1", seq, "18", (short) 0, "S" + seq, "V", "SCHEDULED", null, event, null, 0, false, event, hash);
    }

    private static WriteSet tu(Instant event, String hash, int... sequences) {
        List<TripUpdateRow> rows = java.util.Arrays.stream(sequences)
                .mapToObj(s -> stop(s, event, hash))
                .toList();
        return WriteSet.tripUpdate(
                EtlFixtures.message(EtlSource.GTFS_RT_TRIP_UPDATE, "{}"), hash, DAY + "|T1", event, rows);
    }

    @Test
    void distinctKeysAreKept() {
        List<WriteSet> items = List.of(vp("A", NOW, 1, "h1"), vp("B", NOW, 1, "h2"));
        assertThat(rule.apply(items)).isEqualTo(ChunkRuleResult.keepAll(items));
    }

    @Test
    void case05IdenticalMessagesCollapse() {
        WriteSet first = vp("A", NOW, 1, "h1");
        WriteSet second = vp("A", NOW, 1, "h1");
        ChunkRuleResult result = rule.apply(List.of(first, second));
        assertThat(result.kept()).containsExactly(second);
        assertThat(result.collapsed()).isEqualTo(1);
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    void case06TheNewerTripUpdateWins() {
        WriteSet older = tu(NOW.minusSeconds(60), "h1", 5);
        WriteSet newer = tu(NOW.minusSeconds(30), "h2", 5);
        ChunkRuleResult result = rule.apply(List.of(newer, older));
        assertThat(result.kept()).containsExactly(newer);
        assertThat(result.collapsed()).isEqualTo(1);
    }

    @Test
    void anOlderTripUpdateKeepsTheStopsTheNewerOneDoesNotReport() {
        WriteSet older = tu(NOW.minusSeconds(60), "h1", 4, 5);
        WriteSet newer = tu(NOW.minusSeconds(30), "h2", 5, 6);
        ChunkRuleResult result = rule.apply(List.of(older, newer));
        assertThat(result.collapsed()).isZero();
        assertThat(result.kept()).hasSize(2);
        assertThat(result.kept().getFirst().tripUpdates())
                .extracting(TripUpdateRow::stopSequence)
                .containsExactly(4);
        assertThat(result.kept().get(1)).isEqualTo(newer);
    }

    @Test
    void case07ConflictingPayloadsRejectTheEarlierRecord() {
        WriteSet earlier = vp("A", NOW, 1, "h1");
        WriteSet later = vp("A", NOW, 2, "h2");
        ChunkRuleResult result = rule.apply(List.of(earlier, later));
        assertThat(result.kept()).containsExactly(later);
        assertThat(result.rejected()).singleElement().satisfies(r -> {
            assertThat(r.item()).isEqualTo(earlier);
            assertThat(r.violation().stage()).isEqualTo(DlqStage.DEDUP);
            assertThat(r.violation().ruleId()).isEqualTo("DQ-02");
            assertThat(r.violation().getMessage()).contains("offset " + later.offsetOrZero());
        });
    }

    @Test
    void anOlderConflictIsSimplySuperseded() {
        WriteSet a = tu(NOW.minusSeconds(60), "h1", 5);
        WriteSet b = tu(NOW.minusSeconds(60), "h2", 5);
        WriteSet newest = tu(NOW, "h3", 5);
        ChunkRuleResult result = rule.apply(List.of(a, b, newest));
        assertThat(result.kept()).containsExactly(newest);
        assertThat(result.rejected()).isEmpty();
        assertThat(result.collapsed()).isEqualTo(2);
    }

    @Test
    void emptyItemsPassThrough() {
        WriteSet empty = WriteSet.empty(EtlFixtures.tombstone(EtlSource.TICKETING_SALES));
        WriteSet a = vp("A", NOW, 1, "h1");
        ChunkRuleResult result = rule.apply(List.of(empty, a, vp("A", NOW, 1, "h1")));
        assertThat(result.kept()).contains(empty);
        assertThat(result.collapsed()).isEqualTo(1);
    }
}
