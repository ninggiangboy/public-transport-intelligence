package dev.pti.etl;

import dev.pti.etl.core.EtlSource;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka broker per test JVM. Each test class uses its own topic prefix ({@code pti.kafka.topic-prefix},
 * DOC-44 §6.2), so classes never see each other's messages.
 */
public final class EtlKafka {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    private static boolean started;

    private EtlKafka() {}

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

    /** The four streaming topics under a prefix, with fewer partitions than production. */
    public static void createTopics(String prefix) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        try (Admin admin = Admin.create(props)) {
            admin.createTopics(List.of(
                            new NewTopic(prefix + EtlSource.GTFS_RT_VEHICLE_POSITION.requireTopic(), 3, (short) 1),
                            new NewTopic(prefix + EtlSource.GTFS_RT_TRIP_UPDATE.requireTopic(), 3, (short) 1),
                            new NewTopic(prefix + EtlSource.TICKETING_SALES.requireTopic(), 2, (short) 1),
                            new NewTopic(prefix + EtlSource.TICKETING_SALE_POINTS.requireTopic(), 1, (short) 1)))
                    .all()
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot create topics", e);
        }
    }

    public static KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(), ProducerConfig.ACKS_CONFIG, "all"),
                new StringSerializer(),
                new ByteArraySerializer());
    }

    public static void send(KafkaProducer<String, byte[]> producer, String topic, String key, byte[] value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot send to " + topic, e);
        }
    }
}
