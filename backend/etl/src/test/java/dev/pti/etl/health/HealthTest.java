package dev.pti.etl.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.pti.etl.core.EtlSource;
import dev.pti.etl.flags.RuntimeFlagChanged;
import dev.pti.etl.flags.RuntimeFlags;
import dev.pti.etl.reference.ReferenceData;
import dev.pti.etl.reference.ReferenceDataHolder;
import dev.pti.etl.stream.ListenerPauseCoordinator;
import dev.pti.etl.stream.MicroBatchCommitted;
import dev.pti.etl.stream.SourceActivity;
import dev.pti.etl.stream.StreamChunkResult;
import dev.pti.etl.stream.StreamListener;
import dev.pti.etl.write.WriteMode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HealthTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:20:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void referenceDataIsDownUntilAFeedIsActive() {
        ReferenceDataHolder holder = mock(ReferenceDataHolder.class);
        ReferenceData data = mock(ReferenceData.class);
        when(data.feedVersionId()).thenReturn(7L);
        when(holder.current()).thenReturn(Optional.empty()).thenReturn(Optional.of(data));
        ReferenceDataHealthIndicator indicator = new ReferenceDataHealthIndicator(holder);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsEntry("feedVersionId", 7L);
    }

    private static WarehouseHealthIndicator warehouse(boolean reachable, CircuitBreaker breaker) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        if (!reachable) {
            when(dataSource.getConnection()).thenThrow(new SQLException("refused", "08001"));
        }
        return new WarehouseHealthIndicator(new JdbcTemplate(dataSource), breaker, CLOCK);
    }

    @Test
    void theWarehouseIsDownWhenItDoesNotAnswerOrTheCircuitIsOpen() throws SQLException {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("w");
        WarehouseHealthIndicator down = warehouse(false, breaker);
        assertThat(down.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(down.health().getDetails()).containsKeys("circuitState", "lastErrorAt");
        assertThat(down.isUp()).isFalse();
    }

    /** The gauge reads isUp() on every scrape: it must answer from memory, even with the database unreachable. */
    @Test
    void isUpNeverTouchesTheDatabase() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("refused", "08001"));
        WarehouseHealthIndicator warehouse =
                new WarehouseHealthIndicator(new JdbcTemplate(dataSource), CircuitBreaker.ofDefaults("w"), CLOCK);

        assertThat(warehouse.isUp()).as("before any ping").isTrue();
        verify(dataSource, never()).getConnection();

        assertThat(warehouse.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(warehouse.isUp()).as("after a failed ping").isFalse();
        verify(dataSource, times(1)).getConnection();
    }

    private static SourceActivity activity(EtlSource source, Instant at) {
        SourceActivity activity = new SourceActivity(Clock.fixed(at, ZoneOffset.UTC));
        activity.onCommitted(new MicroBatchCommitted(
                new StreamChunkResult(
                        UUID.randomUUID(),
                        source,
                        WriteMode.BATCH,
                        1,
                        1,
                        0,
                        0,
                        null,
                        null,
                        at.minusSeconds(1),
                        Set.of()),
                at));
        return activity;
    }

    private static ListenerPauseCoordinator pauses(Set<ListenerPauseCoordinator.Reason> reasons, boolean running) {
        ListenerPauseCoordinator pauses = mock(ListenerPauseCoordinator.class);
        when(pauses.isRunning(any())).thenReturn(running);
        when(pauses.reasons(any())).thenReturn(reasons);
        return pauses;
    }

    private static WarehouseHealthIndicator upWarehouse() {
        WarehouseHealthIndicator warehouse = mock(WarehouseHealthIndicator.class);
        when(warehouse.isUp()).thenReturn(true);
        return warehouse;
    }

    private static SourceHealthIndicator gtfs(
            WarehouseHealthIndicator warehouse, ListenerPauseCoordinator pauses, SourceActivity activity) {
        return new SourceHealthIndicator(
                List.of(StreamListener.GTFS_RT_VEHICLE_POSITION, StreamListener.GTFS_RT_TRIP_UPDATE),
                warehouse,
                pauses,
                activity,
                Duration.ofSeconds(30),
                CLOCK,
                null,
                () -> false);
    }

    @Test
    void aSourceIsUpWhenDataArrivedRecently() {
        SourceHealthIndicator indicator = gtfs(
                upWarehouse(),
                pauses(EnumSet.noneOf(ListenerPauseCoordinator.Reason.class), true),
                activity(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(5)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        indicator.bindTo(meters, "gtfs-rt");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsKeys("lastCommitAt", "maxRecordTs", "pausedReasons");
        assertThat(meters.get("pti.source.health").gauge().value()).isEqualTo(1.0);

        assertThat(gtfs(
                                upWarehouse(),
                                pauses(EnumSet.noneOf(ListenerPauseCoordinator.Reason.class), true),
                                activity(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(60)))
                        .health()
                        .getStatus())
                .as("stale data")
                .isEqualTo(Status.DOWN);
    }

    @Test
    void pausesDecideBetweenOutOfServiceAndDown() {
        SourceActivity fresh = activity(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW);
        assertThat(gtfs(upWarehouse(), pauses(EnumSet.of(ListenerPauseCoordinator.Reason.FLAG), true), fresh)
                        .health()
                        .getStatus())
                .isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(gtfs(upWarehouse(), pauses(EnumSet.of(ListenerPauseCoordinator.Reason.BACKOFF), true), fresh)
                        .health()
                        .getStatus())
                .isEqualTo(Status.DOWN);
        assertThat(gtfs(upWarehouse(), pauses(EnumSet.noneOf(ListenerPauseCoordinator.Reason.class), false), fresh)
                        .health()
                        .getStatus())
                .as("a stopped container")
                .isEqualTo(Status.DOWN);
        WarehouseHealthIndicator downWarehouse = mock(WarehouseHealthIndicator.class);
        assertThat(gtfs(downWarehouse, pauses(EnumSet.noneOf(ListenerPauseCoordinator.Reason.class), true), fresh)
                        .health()
                        .getStatus())
                .isEqualTo(Status.DOWN);
    }

    @Test
    void ticketingNeedsTheConnectorAndMayBeQuietWhenCaughtUp() {
        ConnectorStatus running = mock(ConnectorStatus.class);
        when(running.state()).thenReturn("RUNNING");
        ConnectorStatus failed = mock(ConnectorStatus.class);
        when(failed.state()).thenReturn("FAILED");
        SourceActivity quiet = new SourceActivity(CLOCK);
        List<StreamListener> ticketing = List.of(StreamListener.TICKETING_SALES, StreamListener.TICKETING_SALE_POINTS);
        ListenerPauseCoordinator none = pauses(EnumSet.noneOf(ListenerPauseCoordinator.Reason.class), true);

        SourceHealthIndicator caughtUp = new SourceHealthIndicator(
                ticketing, upWarehouse(), none, quiet, Duration.ofSeconds(30), CLOCK, running, () -> true);
        SourceHealthIndicator lagging = new SourceHealthIndicator(
                ticketing, upWarehouse(), none, quiet, Duration.ofSeconds(30), CLOCK, running, () -> false);
        SourceHealthIndicator broken = new SourceHealthIndicator(
                ticketing, upWarehouse(), none, quiet, Duration.ofSeconds(30), CLOCK, failed, () -> true);

        assertThat(caughtUp.health().getStatus()).isEqualTo(Status.UP);
        assertThat(caughtUp.health().getDetails()).containsEntry("connectorState", "RUNNING");
        assertThat(lagging.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(broken.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void theConnectorStateComesFromKafkaConnectAndIsCached() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Instant[] now = {NOW};
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        String url = "http://connect:8083/connectors/debezium-ticketing/status";
        server.expect(requestTo(url))
                .andRespond(withSuccess(
                        "{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"state\":\"RUNNING\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess(
                        "{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"state\":\"FAILED\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess(
                        "{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"connector\":{\"state\":\"PAUSED\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withServerError());
        ConnectorStatus status = new ConnectorStatus(builder, URI.create("http://connect:8083"), clock);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        status.bindTo(meters);

        assertThat(status.isRunning()).isTrue();
        assertThat(meters.get("pti.connect.connector.running").gauge().value())
                .as("cached")
                .isEqualTo(1.0);
        List<String> states = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            now[0] = now[0].plusSeconds(16);
            states.add(status.state());
        }
        assertThat(states).containsExactly("TASK_FAILED", "NO_TASKS", "PAUSED", "UNREACHABLE");
        server.verify();
    }

    @Test
    void runtimeFlagsPublishChangesOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String[]> rows = new ArrayList<>(List.of(
                new String[] {"etl.consumer.gtfs-rt.paused", "false"}, new String[] {"triage.threshold", "\"0.8\""}));
        org.mockito.Mockito.doAnswer(call -> {
                    RowCallbackHandler handler = call.getArgument(1);
                    for (String[] row : rows) {
                        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                        when(rs.getString("key")).thenReturn(row[0]);
                        when(rs.getString("value")).thenReturn(row[1]);
                        handler.processRow(rs);
                    }
                    return null;
                })
                .when(jdbc)
                .query(any(String.class), any(RowCallbackHandler.class));
        List<Object> events = new ArrayList<>();
        RuntimeFlags flags = new RuntimeFlags(jdbc, events::add);

        flags.refresh();
        flags.refresh();
        rows.set(0, new String[] {"etl.consumer.gtfs-rt.paused", "true"});
        flags.refresh();

        assertThat(events)
                .containsExactly(
                        new RuntimeFlagChanged("etl.consumer.gtfs-rt.paused", false),
                        new RuntimeFlagChanged("etl.consumer.gtfs-rt.paused", true));
        assertThat(flags.isEnabled("etl.consumer.gtfs-rt.paused")).isTrue();
        assertThat(flags.isEnabled("unknown")).isFalse();

        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("down"))
                .when(jdbc)
                .query(any(String.class), any(RowCallbackHandler.class));
        flags.refresh();
        assertThat(flags.isEnabled("etl.consumer.gtfs-rt.paused"))
                .as("keeps the last values")
                .isTrue();
    }
}
