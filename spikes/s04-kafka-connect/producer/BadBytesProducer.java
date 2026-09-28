import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Spike S-04: sends a value with invalid UTF-8 and a NUL byte, as the bad-data scenario does. */
public final class BadBytesProducer {
    public static void main(String[] args) throws Exception {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, args[0]);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        byte[] value = {'{', '"', 'v', '"', ':', '"', 'a', (byte) 0xFF, (byte) 0xC3, 'b', 0x00, 'c', '"', '}'};
        try (var producer = new KafkaProducer<String, byte[]>(props)) {
            producer.send(new ProducerRecord<>(args[1], 0, "bad-1", value)).get();
        }
        System.out.println("sent");
    }
}
