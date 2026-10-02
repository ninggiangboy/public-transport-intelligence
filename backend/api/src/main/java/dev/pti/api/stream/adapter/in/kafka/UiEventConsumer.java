package dev.pti.api.stream.adapter.in.kafka;

import dev.pti.api.stream.application.EventHub;
import dev.pti.api.stream.application.port.StreamMetrics;
import dev.pti.api.stream.domain.HubEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.Timestamps;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code pti.events.ui} for the hub of this pod (DOC-26 §4): a consumer group of its own, offsets never
 * committed, and on every assignment a seek to five minutes ago. Records before the end offsets seen at that moment
 * fill the ring buffer without reaching any connection; when every partition has reached its end offset the buffer is
 * ready for {@code Last-Event-ID}. An assignment after the first one means the consumer lost its place, so the hub
 * resets and tells the connections to refetch.
 */
public final class UiEventConsumer implements BatchMessageListener<String, String>, ConsumerAwareRebalanceListener {

    private static final Logger log = LoggerFactory.getLogger(UiEventConsumer.class);

    private final EventHub hub;
    private final StreamMetrics metrics;
    private final BusinessClock clock;
    private final JsonMapper mapper;
    private final Duration window;
    private final Map<TopicPartition, Long> prefillEnd = new ConcurrentHashMap<>();
    private final Set<TopicPartition> catchingUp = ConcurrentHashMap.newKeySet();
    private volatile boolean assignedBefore;

    public UiEventConsumer(
            EventHub hub, StreamMetrics metrics, BusinessClock clock, JsonMapper mapper, Duration window) {
        this.hub = hub;
        this.metrics = metrics;
        this.clock = clock;
        this.mapper = mapper;
        this.window = window;
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions.isEmpty()) {
            return;
        }
        // ULIDs and occurred_at are wall clock, so is the window.
        Instant from = clock.realNow().minus(window);
        hub.startPrefill(from);
        if (assignedBefore) {
            hub.consumerReset();
        }
        assignedBefore = true;
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
        Map<TopicPartition, Long> times = new HashMap<>();
        partitions.forEach(partition -> times.put(partition, from.toEpochMilli()));
        Map<TopicPartition, OffsetAndTimestamp> starts = consumer.offsetsForTimes(times);
        prefillEnd.clear();
        catchingUp.clear();
        for (TopicPartition partition : partitions) {
            long end = ends.getOrDefault(partition, 0L);
            OffsetAndTimestamp start = starts.get(partition);
            long position = start == null ? end : start.offset();
            consumer.seek(partition, position);
            prefillEnd.put(partition, end);
            if (position < end) {
                catchingUp.add(partition);
            }
        }
        log.info("SSE consumer assigned {}; prefilling from {}", partitions, from);
        if (catchingUp.isEmpty()) {
            hub.prefillDone();
        }
    }

    @Override
    public void onMessage(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            Long end = prefillEnd.get(partition);
            boolean live = end == null || record.offset() >= end;
            parse(record).ifPresent(event -> hub.accept(event, live));
            if (end != null && record.offset() + 1 >= end && catchingUp.remove(partition) && catchingUp.isEmpty()) {
                hub.prefillDone();
            }
        }
    }

    /** The envelope of DOC-33 §2.1; an envelope that cannot be read is counted, logged and skipped. */
    Optional<HubEvent> parse(ConsumerRecord<String, String> record) {
        String id = null;
        try {
            JsonNode node = mapper.readTree(record.value());
            id = text(node, "id");
            JsonNode data = node.get("data");
            if (id == null || data == null || !data.isObject()) {
                throw new IllegalArgumentException("id and data are required");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = mapper.treeToValue(data, Map.class);
            String sourceRecordTs = text(node, "source_record_ts");
            return Optional.of(HubEvent.received(
                    id,
                    required(node, "type"),
                    UiChannel.fromWireName(required(node, "channel")),
                    Audience.valueOf(required(node, "audience")),
                    Timestamps.parse(required(node, "occurred_at")),
                    sourceRecordTs == null ? null : Timestamps.parse(sourceRecordTs),
                    text(node, "route_id"),
                    payload));
        } catch (JacksonException | IllegalArgumentException | java.time.format.DateTimeParseException e) {
            metrics.invalidEvent();
            log.warn(
                    "Skipping an unreadable UI event (id {}, {}-{}@{}): {}",
                    id,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
