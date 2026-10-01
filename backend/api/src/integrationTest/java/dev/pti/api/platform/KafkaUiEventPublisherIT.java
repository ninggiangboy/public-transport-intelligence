package dev.pti.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.api.ApiKafka;
import dev.pti.api.platform.adapter.out.kafka.KafkaUiEventPublisher;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.events.UiEvent;
import dev.pti.common.json.MessageJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;

/**
 * DOC-33 §2.1 against a real broker: an event the API publishes appears on {@code pti.events.ui} with the envelope of
 * DOC-09 §6, keyed by the entity so that its events keep their order, and a broker that cannot be reached never makes
 * the caller fail.
 */
class KafkaUiEventPublisherIT {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-29T21:19:31.020Z");

    private final String prefix = ApiKafka.newPrefix();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DefaultKafkaProducerFactory<String, String> producers;
    private ApiKafka.Reader topic;
    private KafkaUiEventPublisher publisher;

    @BeforeEach
    void open() {
        ApiKafka.createTopic(prefix, ApiKafka.UI_TOPIC, 3);
        producers = new DefaultKafkaProducerFactory<>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        ApiKafka.bootstrapServers(),
                        ProducerConfig.ACKS_CONFIG,
                        "1",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                        false,
                        ProducerConfig.LINGER_MS_CONFIG,
                        5),
                new StringSerializer(),
                new StringSerializer());
        publisher = new KafkaUiEventPublisher(new KafkaTemplate<>(producers), prefix + ApiKafka.UI_TOPIC, meters);
        topic = new ApiKafka.Reader(prefix, ApiKafka.UI_TOPIC);
    }

    @AfterEach
    void close() {
        topic.close();
        producers.destroy();
    }

    private static UiEvent alertUpdated(String alertId, String type) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", alertId);
        data.put("type", "BUNCHING");
        data.put("severity", 1);
        data.put("audience", "OPERATIONS");
        data.put("acknowledgedBy", "user:operator");
        data.put("acknowledgedAt", "2026-09-29T21:19:31.020Z");
        data.put("link", "/map?route=18&bunching=6f1c2a9e");
        return UiEvent.of(OCCURRED_AT, type, UiChannel.ALERTS, Audience.OPERATIONS, alertId, "18", null, data);
    }

    @Test
    @DisplayName("A published event appears on the topic with the envelope of the contract and data in camelCase")
    void envelope() {
        String alertId = UUID.randomUUID().toString();

        publisher.publish(alertUpdated(alertId, "alert.updated"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<ConsumerRecord<String, String>> records = topic.polled(r -> alertId.equals(r.key()));
            assertThat(records).hasSize(1);
            JsonNode envelope = MessageJson.mapper().readTree(records.getFirst().value());
            assertThat(envelope.propertyNames())
                    .containsExactly(
                            "id", "type", "channel", "audience", "occurred_at", "source_record_ts", "route_id", "data");
            assertThat(envelope.path("id").asString()).matches("[0-9A-HJKMNP-TV-Z]{26}");
            assertThat(envelope.path("type").asString()).isEqualTo("alert.updated");
            assertThat(envelope.path("channel").asString()).isEqualTo("alerts");
            assertThat(envelope.path("audience").asString()).isEqualTo("OPERATIONS");
            assertThat(envelope.path("occurred_at").asString()).isEqualTo("2026-09-29T21:19:31.020Z");
            assertThat(envelope.path("source_record_ts").isNull()).isTrue();
            assertThat(envelope.path("route_id").asString()).isEqualTo("18");
            assertThat(envelope.path("data").path("acknowledgedBy").asString()).isEqualTo("user:operator");
            assertThat(envelope.path("data").path("link").asString()).isEqualTo("/map?route=18&bunching=6f1c2a9e");
        });
        assertThat(meters.get("pti.ui.events.published")
                        .tag("type", "alert.updated")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("The events of one alert share a key, so a partition and an order")
    void orderPerKey() {
        String alertId = UUID.randomUUID().toString();

        publisher.publishAll(List.of(alertUpdated(alertId, "alert.created"), alertUpdated(alertId, "alert.updated")));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<ConsumerRecord<String, String>> records = topic.polled(r -> alertId.equals(r.key()));
            assertThat(records).hasSize(2);
            assertThat(records)
                    .extracting(ConsumerRecord::partition)
                    .containsOnly(records.getFirst().partition());
            assertThat(records)
                    .extracting(r -> MessageJson.mapper()
                            .readTree(r.value())
                            .path("type")
                            .asString())
                    .containsExactly("alert.created", "alert.updated");
        });
    }

    @Test
    @DisplayName("A broker that cannot be reached costs seconds, never throws, and is counted and logged")
    void brokerDown() {
        DefaultKafkaProducerFactory<String, String> lost = new DefaultKafkaProducerFactory<>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                        ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000),
                new StringSerializer(),
                new StringSerializer());
        try {
            KafkaUiEventPublisher broken =
                    new KafkaUiEventPublisher(new KafkaTemplate<>(lost), prefix + ApiKafka.UI_TOPIC, meters);

            long start = System.nanoTime();
            broken.publish(alertUpdated(UUID.randomUUID().toString(), "alert.updated"));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took).isLessThan(Duration.ofSeconds(10));
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(meters.get("pti.ui.events.publish.errors")
                                    .tag("type", "alert.updated")
                                    .counter()
                                    .count())
                            .isEqualTo(1));
        } finally {
            lost.destroy();
        }
    }
}
