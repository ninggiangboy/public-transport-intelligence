package dev.pti.etl.write;

import static dev.pti.etl.testing.EtlFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.RuleViolationException;
import dev.pti.common.pii.PiiScrubber;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.testing.EtlFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** The small JDBC collaborators of the writer, with the database mocked. */
class JdbcHelpersTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private static DeadLetter letter() {
        return DeadLetter.of(
                EtlFixtures.message(EtlSource.GTFS_RT_VEHICLE_POSITION, "{\"customer_ref\":\"x\"}"),
                new RuleViolationException(DlqStage.QUALITY, "DQ-03", "Route 999 not found"),
                "2050|2026-09-29T21:19:05.000Z",
                UUID.randomUUID());
    }

    @Test
    void liveDeadLettersAreInsertedOnceAndCounted() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1, 0);
        JdbcDeadLetterWriter writer = new JdbcDeadLetterWriter(jdbc, PiiScrubber.withDefaults(), 1024, meters);

        assertThat(writer.write(letter())).isEqualTo(DeadLetterWriter.DeadLetterResult.INSERTED);
        assertThat(writer.write(letter())).isEqualTo(DeadLetterWriter.DeadLetterResult.IGNORED);
        assertThat(meters.find("pti.dlq.records")
                        .tag("result", "inserted")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.find("pti.dq.violations")
                        .tag("rule", "DQ-03")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aReplayUpdateIsLogged() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(Map.of("id", id, "inserted", false, "old_stage", "SCHEMA", "old_error_class", "DQ-01")))
                .thenReturn(List.of(Map.of("id", id, "inserted", true)));
        JdbcDeadLetterWriter writer = new JdbcDeadLetterWriter(jdbc, PiiScrubber.withDefaults(), 1024, meters);

        assertThat(writer.writeReplay(letter())).isEqualTo(DeadLetterWriter.DeadLetterResult.UPDATED);
        verify(jdbc).update(contains("REPLAY_FAILED"), any(SqlParameterSource.class));
        assertThat(writer.writeReplay(letter())).isEqualTo(DeadLetterWriter.DeadLetterResult.INSERTED);
    }

    @Test
    void aLoadDeadLetterCarriesTheSqlState() {
        var cause = new DataIntegrityViolationException(
                "insert failed", new SQLException("new row violates check constraint", "23514"));
        DeadLetter letter =
                DeadLetter.load(EtlFixtures.message(EtlSource.TICKETING_SALES, "{}"), cause, null, UUID.randomUUID());
        assertThat(letter.stage()).isEqualTo(DlqStage.LOAD);
        assertThat(letter.errorMessage()).isEqualTo("[23514] new row violates check constraint");
        assertThat(DeadLetter.load(letter.message(), new IllegalStateException("x"), null, letter.batchId())
                        .errorMessage())
                .isEqualTo("x");
    }

    @Test
    void theRegistryReturnsTheNewHashes() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("h1  ");
        doAnswer(inv -> {
                    inv.getArgument(2, RowCallbackHandler.class).processRow(rs);
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
        DedupRegistry registry = new DedupRegistry(jdbc);

        assertThat(registry.registerNew(EtlSource.GTFS_RT_TRIP_UPDATE, List.of("h1", "h2"), UUID.randomUUID()))
                .containsExactly("h1");
        assertThat(registry.registerNew(EtlSource.GTFS_RT_TRIP_UPDATE, List.of(), UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void refundOriginalsAreLookedUpOnceForTheChunk() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID original = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("transaction_id", UUID.class)).thenReturn(original);
        when(rs.getBigDecimal("amount")).thenReturn(new java.math.BigDecimal("2.50"));
        when(rs.getString("txn_type")).thenReturn("REFUND");
        doAnswer(inv -> {
                    inv.getArgument(2, RowCallbackHandler.class).processRow(rs);
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
        RefundRule rule = new RefundRule(jdbc, EtlFixtures.dq());

        var refund = refund(original);
        ChunkRuleResult result =
                rule.apply(List.of(refund), new WriteContext(UUID.randomUUID(), RunMode.STREAM, false, NOW));
        assertThat(result.rejected())
                .singleElement()
                .satisfies(r -> assertThat(r.violation().getMessage()).contains("itself a refund"));

        ChunkRuleResult off = new RefundRule(
                        jdbc,
                        EtlFixtures.dq(Map.of(
                                "DQ-12", new dev.pti.etl.config.DqProperties.Rule(false),
                                "DQ-13", new dev.pti.etl.config.DqProperties.Rule(false))))
                .apply(List.of(refund), new WriteContext(UUID.randomUUID(), RunMode.STREAM, false, NOW));
        assertThat(off.kept()).containsExactly(refund);
    }

    @Test
    void theKnownKeyCacheOnlyReportsUnknownKeys() {
        KnownKeyCache cache = new KnownKeyCache(10);
        cache.addVehiclesAfterCommit(Set.of("A"));
        cache.addSalePointsAfterCommit(List.of());
        assertThat(cache.unknownVehicles(List.of("B", "A", "B"))).containsExactly("B");
        assertThat(cache.unknownSalePoints(List.of("K"))).containsExactly("K");
    }

    @Test
    void sqlResourcesAreLoadedAndMissingOnesFail() {
        assertThat(SqlResource.load("upsert_vehicle_position")).contains("fact_vehicle_position_pk");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SqlResource.load("nope"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWriteStatsCountSkipsOnlyWhenThereAreSome() {
        WriteStats stats = new WriteStats(meters);
        stats.recordSkipped(EtlSource.TICKETING_SALES, RunMode.BATCH, 0);
        assertThat(meters.find("pti.etl.records").counter()).isNull();
        stats.recordSkipped(EtlSource.TICKETING_SALES, RunMode.BATCH, 2);
        assertThat(meters.find("pti.etl.records").tag("mode", "batch").counter().count())
                .isEqualTo(2);
    }

    private static dev.pti.etl.core.WriteSet refund(UUID of) {
        UUID id = UUID.randomUUID();
        var row = new dev.pti.etl.core.TicketSaleRow(
                java.time.LocalDate.parse("2026-09-29"),
                id,
                "KIOSK-1",
                null,
                null,
                "SINGLE",
                "REFUND",
                new java.math.BigDecimal("1.00"),
                "USD",
                of,
                "COMPLETED",
                false,
                NOW,
                NOW,
                5,
                NOW,
                "h" + id,
                "c");
        return dev.pti.etl.core.WriteSet.ticketSale(
                EtlFixtures.message(EtlSource.TICKETING_SALES, "{}"), row.payloadHash(), id.toString(), row);
    }
}
