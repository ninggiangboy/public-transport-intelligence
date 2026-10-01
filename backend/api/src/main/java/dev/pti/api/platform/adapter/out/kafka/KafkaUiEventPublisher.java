package dev.pti.api.platform.adapter.out.kafka;

import dev.pti.common.events.UiEvent;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.json.MessageJson;
import dev.pti.common.time.Timestamps;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends the UI events the API makes after its own writes (alert ack, Alertmanager webhook, DOC-33 §3) to {@code
 * pti.events.ui} (DOC-33 §2.1, DOC-09 §6). The envelope is snake_case with a lowercase channel and the audience as its
 * constant name; the keys of {@code data} go out as they are (camelCase); the Kafka key is the event's key, which
 * keeps the events of one entity in one partition.
 *
 * <p>Best effort (ADR-0026): a failure, at any step, is logged, counted in {@code
 * pti_ui_events_publish_errors_total{type}} and never thrown. The row in the database stays right, and the UI catches
 * up through the API.
 */
public final class KafkaUiEventPublisher implements UiEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaUiEventPublisher.class);

    private final KafkaTemplate<String, String> template;
    private final String topic;
    private final MeterRegistry meters;

    public KafkaUiEventPublisher(KafkaTemplate<String, String> template, String topic, MeterRegistry meters) {
        this.template = template;
        this.topic = topic;
        this.meters = meters;
    }

    @Override
    public void publish(UiEvent event) {
        try {
            template.send(topic, event.key(), envelope(event)).whenComplete((result, error) -> {
                if (error != null) {
                    failed(event.type(), error);
                } else {
                    published(event.type());
                }
            });
        } catch (RuntimeException e) {
            failed(event.type(), e);
        }
    }

    @Override
    public void publishAll(List<UiEvent> events) {
        events.forEach(this::publish);
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

    private void published(String type) {
        Counter.builder("pti.ui.events.published")
                .tag("type", type)
                .register(meters)
                .increment();
    }

    private void failed(String type, Throwable error) {
        Counter.builder("pti.ui.events.publish.errors")
                .tag("type", type)
                .register(meters)
                .increment();
        log.warn("Cannot publish the UI event {} to {}", type, topic, error);
    }
}
