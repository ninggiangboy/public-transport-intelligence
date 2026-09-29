package dev.pti.etl.stream;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.ErrorKind;
import dev.pti.common.error.FatalException;
import dev.pti.common.error.PtiException;
import dev.pti.common.error.TransientInfraException;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessors;
import dev.pti.etl.write.ChunkWriter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/**
 * What every listener does with a poll (DOC-20 §3): build the request, run the chunk template behind the
 * {@code warehouse} circuit breaker, and turn any failure into {@link TransientInfraException} (retried with back-off)
 * or {@link FatalException} (stops the container), so that the error handler never has to classify.
 */
public class StreamChunkHandler {

    private final StreamChunkTemplate template;
    private final MessageProcessors processors;
    private final ChunkWriter writer;
    private final CircuitBreaker breaker;
    private final ErrorClassifier classifier;
    private final String instanceId;

    public StreamChunkHandler(
            StreamChunkTemplate template,
            MessageProcessors processors,
            ChunkWriter writer,
            CircuitBreaker breaker,
            ErrorClassifier classifier,
            String instanceId) {
        this.template = template;
        this.processors = processors;
        this.writer = writer;
        this.breaker = breaker;
        this.classifier = classifier;
        this.instanceId = instanceId;
    }

    public StreamChunkResult handle(StreamListener listener, List<ConsumerRecord<String, byte[]>> records) {
        StreamChunkRequest request = new StreamChunkRequest(
                UuidCreator.getTimeOrderedEpoch(),
                listener.source(),
                listener.id(),
                listener.groupId(),
                instanceId,
                messages(listener, records),
                false);
        try (EtlMdc _ = EtlMdc.open(request)) {
            return breaker.executeSupplier(() -> execute(request));
        }
    }

    private StreamChunkResult execute(StreamChunkRequest request) {
        try {
            return template.execute(request, processors.forSource(request.source()), writer);
        } catch (DataBatchFailedException | PtiException | CallNotPermittedException e) {
            throw e;
        } catch (RuntimeException e) {
            if (classifier.classify(e) == ErrorKind.TRANSIENT_INFRA) {
                throw new TransientInfraException("Warehouse unavailable: " + e.getMessage(), e);
            }
            throw new FatalException("Chunk " + request.batchId() + " failed: " + e, e);
        }
    }

    static List<InboundMessage> messages(StreamListener listener, List<ConsumerRecord<String, byte[]>> records) {
        List<InboundMessage> messages = new ArrayList<>(records.size());
        for (ConsumerRecord<String, byte[]> r : records) {
            messages.add(new InboundMessage(
                    listener.source(),
                    r.key(),
                    r.value(),
                    r.topic(),
                    r.partition(),
                    r.offset(),
                    r.timestamp() < 0 ? null : Instant.ofEpochMilli(r.timestamp()),
                    headers(r)));
        }
        return messages;
    }

    private static Map<String, String> headers(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header h : record.headers()) {
            if (h.value() != null) {
                headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
            }
        }
        return headers;
    }
}
