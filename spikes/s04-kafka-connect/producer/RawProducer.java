import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Spike S-04 load generator: sends N records to one partition, plus a few records whose
 * CreateTime lies in an earlier UTC hour, to check rotation and event-time partitioning.
 * Usage: RawProducer <bootstrap> <topic> <partition> <count> <oldTimestampIso>
 */
public final class RawProducer {
    private static final String PAD = "x".repeat(360);
    private static final java.util.Random RNG = new java.util.Random(42);

    private static byte[] randomBytes(int n) {
        var b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static void main(String[] args) throws Exception {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, args[0]);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        String topic = args[1];
        int partition = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        long oldTs = Instant.parse(args[4]).toEpochMilli();
        try (var producer = new KafkaProducer<String, String>(props)) {
            for (int i = 0; i < count; i++) {
                // BOUNDARY=1: first half in the earlier hour, then now (monotonic, like a real producer across an hour change).
                // Otherwise: one earlier-hour record every 10,000 (out-of-order CreateTime, worst case for open files).
                long ts = System.getenv("BOUNDARY") != null
                        ? (i < count / 2 ? oldTs + i : System.currentTimeMillis())
                        : (i % 10_000 == 5_000) ? oldTs : System.currentTimeMillis();
                var pad = System.getenv("RANDOM_PAD") == null ? PAD : java.util.HexFormat.of().formatHex(randomBytes(512));
                var value = "{\"vehicle_id\":\"veh-" + (i % 300) + "\",\"seq\":" + i + ",\"lat\":44.9,\"lon\":-93.2,\"pad\":\"" + pad + "\"}";
                var record = new ProducerRecord<>(topic, partition, ts, "veh-" + (i % 300), value);
                record.headers().add("trace_id", ("t-" + i).getBytes(StandardCharsets.UTF_8));
                producer.send(record);
            }
            producer.flush();
        }
        System.out.println("sent " + count);
    }
}
