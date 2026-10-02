package dev.pti.etl.analytics;

import dev.pti.etl.EtlKafka;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads {@code pti.events.ui} under a test prefix from the start, as a client of the topic would. */
public final class UiEventTopic implements AutoCloseable {

    public static final String NAME = "pti.events.ui";

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    public UiEventTopic(String prefix) {
        consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        EtlKafka.bootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "it-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        "false"),
                new StringDeserializer(),
                new StringDeserializer());
        String topic = prefix + NAME;
        consumer.assign(consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList());
    }

    /** Every record read so far plus what one more poll brings. */
    public List<ConsumerRecord<String, String>> poll() {
        consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
        return List.copyOf(seen);
    }

    public List<ConsumerRecord<String, String>> polled(Predicate<ConsumerRecord<String, String>> filter) {
        return poll().stream().filter(filter).toList();
    }

    @Override
    public void close() {
        consumer.close(CloseOptions.timeout(Duration.ofSeconds(2)));
    }
}
