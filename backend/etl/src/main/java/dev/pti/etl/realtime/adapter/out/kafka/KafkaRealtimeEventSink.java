package dev.pti.etl.realtime.adapter.out.kafka;

import dev.pti.common.events.UiEvent;
import dev.pti.common.json.MessageJson;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.Timestamps;
import dev.pti.etl.realtime.application.port.RealtimeEventSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link RealtimeEventSink} on the UI event producer (DOC-09 §1.2, §6): the snake_case envelope with the lowercase
 * channel, keyed by the event's key. A failure is logged, counted in {@code pti_ui_events_publish_errors_total{type}}
 * and swallowed; a success counts in {@code pti_ui_events_published_total} and {@code pti_ui_commit_to_publish}.
 */
public final class KafkaRealtimeEventSink implements RealtimeEventSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaRealtimeEventSink.class);

    private final KafkaTemplate<String, String> template;
    private final String topic;
    private final BusinessClock clock;
    private final MeterRegistry meters;

    public KafkaRealtimeEventSink(
            KafkaTemplate<String, String> template, String topic, BusinessClock clock, MeterRegistry meters) {
        this.template = template;
        this.topic = topic;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public void publish(UiEvent event, Instant committedAt) {
        try {
            template.send(topic, event.key(), envelope(event)).whenComplete((result, error) -> {
                if (error != null) {
                    failed(event.type(), error);
                } else {
                    published(event.type(), committedAt);
                }
            });
        } catch (RuntimeException e) {
            failed(event.type(), e);
        }
    }

    static String envelope(UiEvent ui) {
        var mapper = MessageJson.mapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", ui.id());
        node.put("type", ui.type());
        node.put("channel", ui.channel().wireName());
        node.put("audience", ui.audience().name());
        node.put("occurred_at", Timestamps.format(ui.occurredAt()));
        if (ui.sourceRecordTs() == null) {
            node.putNull("source_record_ts");
        } else {
            node.put("source_record_ts", Timestamps.format(ui.sourceRecordTs()));
        }
        if (ui.routeId() == null) {
            node.putNull("route_id");
        } else {
            node.put("route_id", ui.routeId());
        }
        node.set("data", mapper.valueToTree(ui.data()));
        return mapper.writeValueAsString(node);
    }

    private void published(String type, Instant committedAt) {
        Counter.builder("pti.ui.events.published")
                .tag("type", type)
                .register(meters)
                .increment();
        Duration sinceCommit = Duration.between(committedAt, clock.realNow());
        Timer.builder("pti.ui.commit.to.publish")
                .tag("type", type)
                .register(meters)
                .record(sinceCommit.isNegative() ? Duration.ZERO : sinceCommit);
    }

    private void failed(String type, Throwable error) {
        Counter.builder("pti.ui.events.publish.errors")
                .tag("type", type)
                .register(meters)
                .increment();
        log.warn("Could not publish UI event {}: {}", type, error.toString());
    }
}
