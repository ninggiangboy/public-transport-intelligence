package dev.pti.etl.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.FatalException;
import dev.pti.common.error.TransientInfraException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.MessageProcessors;
import dev.pti.etl.flags.RuntimeFlagChanged;
import dev.pti.etl.reference.ReferenceDataHolder;
import dev.pti.etl.write.ChunkWriter;
import dev.pti.etl.write.WriteMode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class StreamRuntimeTest {

    /** A container that remembers pause and resume like the real one. */
    private static MessageListenerContainer container(boolean running) {
        MessageListenerContainer c = mock(MessageListenerContainer.class);
        boolean[] paused = {false};
        boolean[] started = {running};
        when(c.isRunning()).thenAnswer(i -> started[0]);
        when(c.isPauseRequested()).thenAnswer(i -> paused[0]);
        org.mockito.Mockito.doAnswer(i -> paused[0] = true).when(c).pause();
        org.mockito.Mockito.doAnswer(i -> paused[0] = false).when(c).resume();
        org.mockito.Mockito.doAnswer(i -> started[0] = true).when(c).start();
        return c;
    }

    private static KafkaListenerEndpointRegistry registry(MessageListenerContainer c) {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainer(any())).thenReturn(c);
        return registry;
    }

    @Test
    void aContainerResumesOnlyWhenNoReasonIsLeft() {
        MessageListenerContainer c = container(true);
        MessageListenerContainer sales = container(true);
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer other = container(true);
        when(registry.getListenerContainer(any())).thenReturn(other);
        when(registry.getListenerContainer("gtfs-rt-vehicle-position")).thenReturn(c);
        when(registry.getListenerContainer("ticketing-sales")).thenReturn(sales);
        ListenerPauseCoordinator pauses = new ListenerPauseCoordinator(registry);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        pauses.bindTo(meters, new TopicNames("t."));

        pauses.onFlag(new RuntimeFlagChanged(StreamListener.GTFS_RT_FLAG, true));
        pauses.onCircuit(CircuitBreaker.StateTransition.CLOSED_TO_OPEN);
        assertThat(c.isPauseRequested()).isTrue();
        assertThat(pauses.reasons(StreamListener.GTFS_RT_VEHICLE_POSITION))
                .containsExactlyInAnyOrder(
                        ListenerPauseCoordinator.Reason.FLAG, ListenerPauseCoordinator.Reason.CIRCUIT);
        assertThat(meters.get("pti.etl.listener.paused")
                        .tag("listener", "gtfs-rt-vehicle-position")
                        .tag("reason", "flag")
                        .gauge()
                        .value())
                .isEqualTo(1.0);

        pauses.onCircuit(CircuitBreaker.StateTransition.OPEN_TO_HALF_OPEN);
        assertThat(c.isPauseRequested()).as("the flag still holds it").isTrue();
        pauses.onFlag(new RuntimeFlagChanged(StreamListener.GTFS_RT_FLAG, false));
        assertThat(pauses.shouldBePaused(StreamListener.GTFS_RT_VEHICLE_POSITION))
                .isFalse();
        assertThat(c.isPauseRequested()).isFalse();

        sales.pause();
        assertThat(pauses.isPausedFor(StreamListener.TICKETING_SALES, ListenerPauseCoordinator.Reason.BACKOFF))
                .as("a pause nobody asked for is the error handler's back-off")
                .isTrue();
        pauses.reconcile();
        assertThat(sales.isPauseRequested()).isFalse();
        assertThat(pauses.isRunning(StreamListener.TICKETING_SALES)).isTrue();
        assertThat(meters.get("pti.etl.listener.running")
                        .tag("listener", "ticketing-sales")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
    }

    @Test
    void gtfsRealtimeListenersStartOnceReferenceDataIsLoaded() {
        MessageListenerContainer c = container(false);
        KafkaListenerEndpointRegistry registry = registry(c);
        ListenerPauseCoordinator pauses = new ListenerPauseCoordinator(registry);
        ReferenceDataHolder reference = mock(ReferenceDataHolder.class);
        when(reference.isLoaded()).thenReturn(false, true);
        ListenerLifecycleManager manager = new ListenerLifecycleManager(registry, reference, pauses);

        manager.onReady();
        verify(c, never()).start();

        pauses.onFlag(new RuntimeFlagChanged(StreamListener.GTFS_RT_FLAG, true));
        manager.onReferenceData(null);

        verify(c).start();
        assertThat(c.isPauseRequested())
                .as("started paused because of the flag")
                .isTrue();
    }

    @Test
    void sourceActivityKeepsTheLastCommitPerSource() {
        Instant now = Instant.parse("2026-09-29T21:20:00Z");
        SourceActivity activity = new SourceActivity(Clock.fixed(now, ZoneOffset.UTC));
        assertThat(activity.lastCommit(EtlSource.TICKETING_SALES)).isEmpty();

        activity.onCommitted(new MicroBatchCommitted(result(EtlSource.TICKETING_SALES, now.minusSeconds(5)), now));
        activity.onCommitted(new MicroBatchCommitted(result(EtlSource.TICKETING_SALES, now.minusSeconds(9)), now));
        activity.onCommitted(new MicroBatchCommitted(result(EtlSource.TICKETING_SALES, null), now));

        assertThat(activity.lastCommit(EtlSource.TICKETING_SALES)).contains(now);
        assertThat(activity.lastRecordTimestamp(EtlSource.TICKETING_SALES)).contains(now.minusSeconds(5));
    }

    static StreamChunkResult result(EtlSource source, Instant recordTs) {
        return new StreamChunkResult(
                UUID.randomUUID(), source, WriteMode.BATCH, 1, 1, 0, 0, null, null, recordTs, Set.of());
    }

    private static ConsumerRecord<String, byte[]> record(long timestamp) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("schema_version", "2".getBytes(StandardCharsets.UTF_8));
        headers.add("empty", null);
        return new ConsumerRecord<>(
                "ticketing.sales.cdc",
                1,
                42,
                timestamp,
                TimestampType.CREATE_TIME,
                0,
                0,
                "k",
                "{}".getBytes(StandardCharsets.UTF_8),
                headers,
                java.util.Optional.empty());
    }

    @Test
    void theHandlerBuildsTheRequestAndTranslatesFailures() {
        StreamChunkTemplate template = mock(StreamChunkTemplate.class);
        MessageProcessor processor = mock(MessageProcessor.class);
        when(processor.source()).thenReturn(EtlSource.TICKETING_SALES);
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("test");
        StreamChunkHandler handler = new StreamChunkHandler(
                template,
                new MessageProcessors(List.of(processor)),
                mock(ChunkWriter.class),
                breaker,
                new ErrorClassifier(),
                "pod-1");
        StreamChunkResult ok = result(EtlSource.TICKETING_SALES, null);
        ArgumentCaptor<StreamChunkRequest> request = ArgumentCaptor.forClass(StreamChunkRequest.class);
        when(template.execute(request.capture(), any(), any()))
                .thenReturn(ok)
                .thenThrow(new QueryTimeoutException("slow"))
                .thenThrow(new IllegalStateException("bug"))
                .thenThrow(new TransientInfraException("down", null));

        assertThat(handler.handle(StreamListener.TICKETING_SALES, List.of(record(1000), record(-1))))
                .isSameAs(ok);
        StreamChunkRequest sent = request.getValue();
        assertThat(sent.instanceId()).isEqualTo("pod-1");
        assertThat(sent.messages()).hasSize(2);
        assertThat(sent.messages().getFirst().headers())
                .containsEntry("schema_version", "2")
                .doesNotContainKey("empty");
        assertThat(sent.messages().getFirst().recordTimestamp()).isEqualTo(Instant.ofEpochMilli(1000));
        assertThat(sent.messages().get(1).recordTimestamp()).isNull();
        assertThat(MDC.get(EtlMdc.BATCH_ID)).isNull();

        assertThatThrownBy(() -> handler.handle(StreamListener.TICKETING_SALES, List.of(record(1))))
                .isInstanceOf(TransientInfraException.class);
        assertThatThrownBy(() -> handler.handle(StreamListener.TICKETING_SALES, List.of(record(1))))
                .isInstanceOf(FatalException.class);
        assertThatThrownBy(() -> handler.handle(StreamListener.TICKETING_SALES, List.of(record(1))))
                .isInstanceOf(TransientInfraException.class);
    }

    @Test
    void everyListenerHandsItsPollToTheHandler() {
        StreamChunkHandler handler = mock(StreamChunkHandler.class);
        EtlListeners listeners = new EtlListeners(handler);
        List<ConsumerRecord<String, byte[]>> poll = List.of(record(1));

        listeners.onVehiclePositions(poll);
        listeners.onTripUpdates(poll);
        listeners.onSales(poll);
        listeners.onSalePoints(poll);

        for (StreamListener l : StreamListener.values()) {
            verify(handler).handle(l, poll);
        }
        assertThat(new TopicNames("x.").named("TICKETING_SALES")).isEqualTo("x.ticketing.sales.cdc");
        assertThat(StreamListener.pausedBy(StreamListener.TICKETING_FLAG))
                .containsExactly(StreamListener.TICKETING_SALES, StreamListener.TICKETING_SALE_POINTS);
        assertThat(StreamListener.TICKETING_SALES.needsReferenceData()).isFalse();
        assertThat(StreamListener.GTFS_RT_TRIP_UPDATE.containerId(true)).isEqualTo("gtfs-rt-trip-update-baseline");
        assertThat(StreamListener.GTFS_RT_TRIP_UPDATE.containerId(false)).isEqualTo("gtfs-rt-trip-update");
        BaselineListeners baseline = new BaselineListeners(handler);
        baseline.onVehiclePositions(poll);
        baseline.onTripUpdates(poll);
        verify(handler, org.mockito.Mockito.times(2)).handle(StreamListener.GTFS_RT_VEHICLE_POSITION, poll);
    }
}
