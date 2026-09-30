package dev.pti.etl.analytics.adapter.out.kafka;

import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.UiEvent;
import dev.pti.common.json.MessageJson;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.Timestamps;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends the UI events of analytics to {@code pti.events.ui} (DOC-23 §3, DOC-09 §6, DOC-33 §2.1). Each event gets the
 * envelope: a ULID {@code id} and the real time as {@code occurred_at}; the Kafka key is the event's key, so the
 * events of one episode stay in one partition. Channel and audience go out in the spelling of the envelope, the keys
 * of {@code data} as they are (camelCase).
 *
 * <p>Best effort (ADR-0026): a failure, at any step, is logged, counted in
 * {@code pti_ui_events_publish_errors_total{type}} and never thrown. The row in the database stays right, and the UI
 * catches up through the API.
 */
public class KafkaAnalyticsEventSink implements AnalyticsEventSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaAnalyticsEventSink.class);

    private final KafkaTemplate<String, String> template;
    private final String topic;
    private final BusinessClock clock;
    private final MeterRegistry meters;

    public KafkaAnalyticsEventSink(
            KafkaTemplate<String, String> template, String topic, BusinessClock clock, MeterRegistry meters) {
        this.template = template;
        this.topic = topic;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public void publish(List<InsightEvent> events) {
        for (InsightEvent event : events) {
            try {
                send(event);
            } catch (RuntimeException e) {
                failed(event.type(), e);
            }
        }
    }

    private void send(InsightEvent event) {
        UiEvent ui = UiEvent.of(
                clock.realNow(),
                event.type(),
                event.channel(),
                event.audience(),
                event.key(),
                event.routeId(),
                event.sourceRecordTs(),
                event.data());
        template.send(topic, ui.key(), envelope(ui)).whenComplete((result, error) -> {
            if (error != null) {
                failed(ui.type(), error);
            } else {
                published(ui.type(), event.committedAt());
            }
        });
    }

    /** The JSON of DOC-09 §6. Absent optional fields are written as {@code null}, as in the contract. */
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
        if (committedAt != null) {
            Duration sinceCommit = Duration.between(committedAt, clock.realNow());
            Timer.builder("pti.ui.commit.to.publish")
                    .tag("type", type)
                    .register(meters)
                    .record(sinceCommit.isNegative() ? Duration.ZERO : sinceCommit);
        }
    }

    private void failed(String type, Throwable error) {
        Counter.builder("pti.ui.events.publish.errors")
                .tag("type", type)
                .register(meters)
                .increment();
        log.warn("Cannot publish the UI event {} to {}", type, topic, error);
    }
}
