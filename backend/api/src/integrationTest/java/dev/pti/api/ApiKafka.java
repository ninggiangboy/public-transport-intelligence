package dev.pti.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka broker per test JVM, as the other apps do (DOC-44 §6.2): a test class works under its own topic prefix, so
 * classes never see each other's messages. {@link Reader} reads {@code pti.events.ui} from the start, as a client of
 * the topic would.
 */
public final class ApiKafka {

    public static final String UI_TOPIC = "pti.events.ui";

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    private static boolean started;

    private ApiKafka() {}

    public static synchronized String bootstrapServers() {
        if (!started) {
            KAFKA.start();
            started = true;
        }
        return KAFKA.getBootstrapServers();
    }

    public static String newPrefix() {
        return "it" + UUID.randomUUID().toString().substring(0, 8) + ".";
    }

    /** The topic under the prefix; a second call for the same topic is fine. */
    public static void createTopic(String prefix, String name, int partitions) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        try (Admin admin = Admin.create(props)) {
            admin.createTopics(List.of(new NewTopic(prefix + name, partitions, (short) 1)))
                    .all()
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("Cannot create topic " + prefix + name, e);
            }
        }
    }

    /** Reads a topic from the start. */
    public static final class Reader implements AutoCloseable {

        private final KafkaConsumer<String, String> consumer;
        private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

        public Reader(String prefix, String topic) {
            consumer = new KafkaConsumer<>(
                    Map.of(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            bootstrapServers(),
                            ConsumerConfig.GROUP_ID_CONFIG,
                            "it-" + UUID.randomUUID(),
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                            "earliest",
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                            "false"),
                    new StringDeserializer(),
                    new StringDeserializer());
            String name = prefix + topic;
            consumer.assign(consumer.partitionsFor(name).stream()
                    .map(info -> new TopicPartition(name, info.partition()))
                    .toList());
        }

        /** Every record read so far that passes the filter, plus what one more poll brings. */
        public List<ConsumerRecord<String, String>> polled(Predicate<ConsumerRecord<String, String>> filter) {
            consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
            return seen.stream().filter(filter).toList();
        }

        @Override
        public void close() {
            consumer.close(CloseOptions.timeout(Duration.ofSeconds(2)));
        }
    }
}
