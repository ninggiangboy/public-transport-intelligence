package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.json.MessageJson;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.EtlKafka;
import dev.pti.etl.analytics.adapter.out.kafka.KafkaAnalyticsEventSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;

/**
 * P4-07: an event published by analytics appears on {@code pti.events.ui} with the envelope of DOC-09 §6 and
 * DOC-33 §2.1, against a real broker.
 */
class KafkaAnalyticsEventSinkIT {

    private final String prefix = EtlKafka.newPrefix();
    private static final Instant NOW = Instant.parse("2026-09-29T21:19:31.020Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DefaultKafkaProducerFactory<String, String> producers;
    private UiEventTopic topic;
    private KafkaAnalyticsEventSink sink;

    @BeforeEach
    void open() {
        EtlKafka.createTopic(prefix, UiEventTopic.NAME, 3);
        producers = new DefaultKafkaProducerFactory<>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        EtlKafka.bootstrapServers(),
                        ProducerConfig.ACKS_CONFIG,
                        "1",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                        false,
                        ProducerConfig.LINGER_MS_CONFIG,
                        5),
                new StringSerializer(),
                new StringSerializer());
        sink = new KafkaAnalyticsEventSink(
                new KafkaTemplate<>(producers),
                prefix + UiEventTopic.NAME,
                new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO),
                meters);
        topic = new UiEventTopic(prefix);
    }

    @AfterEach
    void close() {
        topic.close();
        producers.destroy();
    }

    private static InsightEvent bunchingOpened(String episodeId, Instant sourceRecordTs, Instant committedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", episodeId);
        data.put("routeId", "18");
        data.put("directionId", 0);
        data.put("vehicleLeader", "1234");
        data.put("vehicleFollower", "1250");
        data.put("gapSeconds", 150);
        data.put("headwaySeconds", 600);
        data.put("stopId", "51405");
        data.put("episodeStart", Instant.parse("2026-09-29T21:19:30Z"));
        return new InsightEvent(
                "bunching.opened",
                UiChannel.ALERTS,
                Audience.OPERATIONS,
                episodeId,
                "18",
                sourceRecordTs,
                committedAt,
                data);
    }

    @Test
    void aPublishedEventAppearsOnTheTopicWithTheEnvelopeOfTheContract() {
        String episode = UUID.randomUUID().toString();

        sink.publish(List.of(bunchingOpened(episode, Instant.parse("2026-09-29T21:19:30.107Z"), NOW.minusMillis(40))));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<ConsumerRecord<String, String>> records = topic.polled(r -> episode.equals(r.key()));
            assertThat(records).hasSize(1);
            JsonNode envelope = MessageJson.mapper().readTree(records.getFirst().value());
            assertThat(envelope.propertyNames())
                    .containsExactly(
                            "id", "type", "channel", "audience", "occurred_at", "source_record_ts", "route_id", "data");
            assertThat(envelope.path("id").asString()).matches("[0-9A-HJKMNP-TV-Z]{26}");
            assertThat(envelope.path("type").asString()).isEqualTo("bunching.opened");
            assertThat(envelope.path("channel").asString()).isEqualTo("alerts");
            assertThat(envelope.path("audience").asString()).isEqualTo("OPERATIONS");
            assertThat(envelope.path("occurred_at").asString()).isEqualTo("2026-09-29T21:19:31.020Z");
            assertThat(envelope.path("source_record_ts").asString()).isEqualTo("2026-09-29T21:19:30.107Z");
            assertThat(envelope.path("route_id").asString()).isEqualTo("18");
            JsonNode data = envelope.path("data");
            assertThat(data.propertyNames())
                    .containsExactly(
                            "id",
                            "routeId",
                            "directionId",
                            "vehicleLeader",
                            "vehicleFollower",
                            "gapSeconds",
                            "headwaySeconds",
                            "stopId",
                            "episodeStart");
            assertThat(data.path("id").asString()).isEqualTo(episode);
            assertThat(data.path("gapSeconds").asInt()).isEqualTo(150);
            assertThat(data.path("episodeStart").asString()).isEqualTo("2026-09-29T21:19:30.000Z");
        });
        assertThat(meters.get("pti.ui.events.published")
                        .tag("type", "bunching.opened")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("pti.ui.commit.to.publish")
                        .tag("type", "bunching.opened")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void theEventsOfOneEpisodeShareAKeyAndSoAPartitionAndAnOrder() {
        String episode = UUID.randomUUID().toString();
        InsightEvent opened = bunchingOpened(episode, null, null);
        InsightEvent closed = new InsightEvent(
                "bunching.closed",
                UiChannel.ALERTS,
                Audience.OPERATIONS,
                episode,
                "18",
                null,
                null,
                Map.of("id", episode, "routeId", "18", "closeReason", "GAP_RECOVERED"));

        sink.publish(List.of(opened, closed));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<ConsumerRecord<String, String>> records = topic.polled(r -> episode.equals(r.key()));
            assertThat(records).hasSize(2);
            assertThat(records)
                    .extracting(ConsumerRecord::partition)
                    .containsOnly(records.getFirst().partition());
            assertThat(records)
                    .extracting(r -> MessageJson.mapper()
                            .readTree(r.value())
                            .path("type")
                            .asString())
                    .containsExactly("bunching.opened", "bunching.closed");
            JsonNode closedEnvelope =
                    MessageJson.mapper().readTree(records.get(1).value());
            assertThat(closedEnvelope.path("source_record_ts").isNull())
                    .as("a tick has no source record time")
                    .isTrue();
            assertThat(closedEnvelope.path("data").path("closeReason").asString())
                    .isEqualTo("GAP_RECOVERED");
        });
    }

    @Test
    void aBrokerThatCannotBeReachedCostsSecondsNotMinutesAndNeverThrows() {
        DefaultKafkaProducerFactory<String, String> lost = new DefaultKafkaProducerFactory<>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                        ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000),
                new StringSerializer(),
                new StringSerializer());
        try {
            KafkaAnalyticsEventSink broken = new KafkaAnalyticsEventSink(
                    new KafkaTemplate<>(lost),
                    prefix + UiEventTopic.NAME,
                    new BusinessClock(Clock.systemUTC(), Duration.ZERO),
                    meters);

            long start = System.nanoTime();
            broken.publish(List.of(bunchingOpened(UUID.randomUUID().toString(), null, null)));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took).isLessThan(Duration.ofSeconds(10));
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(meters.get("pti.ui.events.publish.errors")
                                    .tag("type", "bunching.opened")
                                    .counter()
                                    .count())
                            .isEqualTo(1));
        } finally {
            lost.destroy();
        }
    }
}
