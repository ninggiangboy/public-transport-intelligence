package dev.pti.spike.kafka;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Batch listener that fails once with a transient error, like a database outage (DOC-20 §5). */
@Component
public class ProbeListener {

    public final List<String> processed = new CopyOnWriteArrayList<>();
    public final AtomicBoolean failNext = new AtomicBoolean(false);

    @KafkaListener(topics = "probe", groupId = "spike", batch = "true", autoStartup = "${spike.kafka.enabled:false}")
    void onBatch(List<ConsumerRecord<String, String>> records) {
        if (failNext.compareAndSet(true, false)) {
            throw new TransientDataAccessResourceException("Injected transient error");
        }
        records.forEach(r -> processed.add(r.value()));
    }
}
