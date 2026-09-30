package dev.pti.etl.analytics.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.json.MessageJson;
import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;

/** The envelope of DOC-09 §6 and the best-effort contract of DOC-23 §3. */
class KafkaAnalyticsEventSinkTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:19:31.020Z");
    private static final String TOPIC = "pti.events.ui";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final KafkaAnalyticsEventSink sink = new KafkaAnalyticsEventSink(
            template, TOPIC, new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO), registry);

    private static InsightEvent event(String type, String routeId, Instant sourceRecordTs, Instant committedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c");
        data.put("routeId", routeId);
        data.put("episodeStart", Instant.parse("2026-09-29T21:19:00Z"));
        data.put("closeReason", null);
        data.put("affectedStopIds", List.of("A", "B"));
        return new InsightEvent(
                type,
                UiChannel.ALERTS,
                Audience.OPERATIONS,
                "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c",
                routeId,
                sourceRecordTs,
                committedAt,
                data);
    }

    private JsonNode sentEnvelope() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq(TOPIC), eq("6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c"), value.capture());
        return MessageJson.mapper().readTree(value.getValue());
    }

    @Test
    void theEnvelopeHasTheFieldsOfTheContract() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        sink.publish(List.of(event("bunching.opened", "18", Instant.parse("2026-09-29T21:19:30.107Z"), NOW)));

        JsonNode envelope = sentEnvelope();
        assertThat(envelope.propertyNames())
                .containsExactly(
                        "id", "type", "channel", "audience", "occurred_at", "source_record_ts", "route_id", "data");
        assertThat(envelope.path("id").asString()).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(envelope.path("type").asString()).isEqualTo("bunching.opened");
        assertThat(envelope.path("channel").asString())
                .as("lowercase on the wire")
                .isEqualTo("alerts");
        assertThat(envelope.path("audience").asString()).isEqualTo("OPERATIONS");
        assertThat(envelope.path("occurred_at").asString()).isEqualTo("2026-09-29T21:19:31.020Z");
        assertThat(envelope.path("source_record_ts").asString()).isEqualTo("2026-09-29T21:19:30.107Z");
        assertThat(envelope.path("route_id").asString()).isEqualTo("18");
    }

    @Test
    void dataKeepsItsCamelCaseKeysAndWritesInstantsInTheMessageFormat() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        sink.publish(List.of(event("bunching.closed", "18", null, null)));

        JsonNode data = sentEnvelope().path("data");
        assertThat(data.path("routeId").asString()).isEqualTo("18");
        assertThat(data.path("episodeStart").asString()).isEqualTo("2026-09-29T21:19:00.000Z");
        assertThat(data.has("closeReason")).isTrue();
        assertThat(data.path("closeReason").isNull()).isTrue();
        assertThat(data.path("affectedStopIds")).hasSize(2);
    }

    @Test
    void anEventWithoutARecordTimeOrRouteWritesNullForBoth() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        sink.publish(List.of(new InsightEvent(
                "alert.created",
                UiChannel.ALERTS,
                Audience.PUBLIC,
                "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c",
                null,
                null,
                null,
                Map.of())));

        JsonNode envelope = sentEnvelope();
        assertThat(envelope.has("source_record_ts")).isTrue();
        assertThat(envelope.path("source_record_ts").isNull()).isTrue();
        assertThat(envelope.path("route_id").isNull()).isTrue();
        assertThat(envelope.path("audience").asString()).isEqualTo("PUBLIC");
    }

    @Test
    void everyEventGetsItsOwnUlid() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        sink.publish(List.of(event("bunching.opened", "18", null, null), event("bunching.opened", "18", null, null)));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).send(eq(TOPIC), anyString(), value.capture());
        List<String> ids = value.getAllValues().stream()
                .map(json -> MessageJson.mapper().readTree(json).path("id").asString())
                .toList();
        assertThat(ids).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void anAcknowledgedEventIsCountedAndTimedFromTheCommit() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        sink.publish(List.of(event("bunching.opened", "18", null, NOW.minusMillis(450))));

        assertThat(registry.get("pti.ui.events.published")
                        .tag("type", "bunching.opened")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("pti.ui.commit.to.publish")
                        .tag("type", "bunching.opened")
                        .timer()
                        .totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(450);
    }

    @Test
    void anEventWithoutACommitTimeIsNotTimed() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        sink.publish(List.of(event("alert.created", "18", null, null)));

        assertThat(registry.find("pti.ui.commit.to.publish").timer()).isNull();
        assertThat(registry.get("pti.ui.events.published").counter().count()).isEqualTo(1);
    }

    @Test
    void aSendThatFailsLaterIsCountedAndNeverThrown() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker is down")));

        sink.publish(List.of(event("bunching.opened", "18", null, NOW)));

        assertThat(registry.get("pti.ui.events.publish.errors")
                        .tag("type", "bunching.opened")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.find("pti.ui.events.published").counter()).isNull();
        assertThat(registry.find("pti.ui.commit.to.publish").timer()).isNull();
    }

    @Test
    void aSendThatThrowsAtOnceIsCountedAndTheNextEventIsStillSent() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("producer is closed"))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        sink.publish(List.of(event("bunching.opened", "18", null, NOW), event("bunching.closed", "18", null, NOW)));

        assertThat(registry.get("pti.ui.events.publish.errors")
                        .tag("type", "bunching.opened")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("pti.ui.events.published")
                        .tag("type", "bunching.closed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void anEventThatCannotBeBuiltIsCountedAndNothingIsSent() {
        Map<String, Object> huge = Map.of("blob", "x".repeat(70_000));
        InsightEvent event =
                new InsightEvent("alert.created", UiChannel.ALERTS, Audience.PUBLIC, "k", null, null, null, huge);

        sink.publish(List.of(event));

        verify(template, never()).send(anyString(), anyString(), anyString());
        assertThat(registry.get("pti.ui.events.publish.errors").counter().count())
                .isEqualTo(1);
    }

    @Test
    void anEmptyListSendsNothing() {
        sink.publish(List.of());

        verify(template, never()).send(anyString(), anyString(), anyString());
    }
}
