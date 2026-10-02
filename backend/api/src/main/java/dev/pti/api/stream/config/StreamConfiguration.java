package dev.pti.api.stream.config;

import dev.pti.api.stream.adapter.in.kafka.UiEventConsumer;
import dev.pti.api.stream.adapter.in.scheduling.StreamSchedules;
import dev.pti.api.stream.adapter.in.sse.SseFrameWriter;
import dev.pti.api.stream.adapter.in.sse.SseShutdown;
import dev.pti.api.stream.adapter.in.sse.StreamEndpointSettings;
import dev.pti.api.stream.adapter.out.metrics.MicrometerStreamMetrics;
import dev.pti.api.stream.application.EventHub;
import dev.pti.api.stream.application.EventRingBuffer;
import dev.pti.api.stream.application.StreamSettings;
import dev.pti.api.stream.application.VehicleThrottle;
import dev.pti.api.stream.application.port.StreamMetrics;
import dev.pti.common.events.UiChannel;
import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SSE feature (DOC-26): the hub with its ring buffer and vehicle throttle, the consumer of
 * {@code pti.events.ui} (one group per pod, no commits), the endpoint's settings, the schedules, the shutdown hook and
 * the gauges of DOC-26 §12. With {@code pti.api.sse.enabled=false} there is no consumer and {@code /stream} is 503.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StreamProperties.class)
class StreamConfiguration {

    static final String EVENTS_UI_TOPIC = "pti.events.ui";

    @Bean
    StreamMetrics streamMetrics(MeterRegistry registry) {
        return new MicrometerStreamMetrics(registry);
    }

    @Bean
    EventHub eventHub(
            StreamProperties sse,
            @Value("${pti.api.rate-limit.enabled}") boolean capsEnabled,
            @Value("${pti.api.rate-limit.sse-per-ip}") int perIp,
            @Value("${pti.api.rate-limit.sse-per-user}") int perUser,
            StreamMetrics metrics,
            BusinessClock clock,
            MeterRegistry registry) {
        StreamSettings settings = new StreamSettings(
                sse.bufferWindow(),
                sse.bufferMaxEvents(),
                sse.connectionQueue(),
                sse.writeStallTimeout(),
                sse.vehiclesPerSecond(),
                sse.maxConnections(),
                sse.replayClockSkew(),
                sse.readyWait(),
                sse.maxRouteFilter(),
                capsEnabled,
                perIp,
                perUser);
        EventHub hub = new EventHub(
                new EventRingBuffer(sse.bufferWindow(), sse.bufferMaxEvents()),
                new VehicleThrottle(sse.vehiclesPerSecond()),
                metrics,
                clock,
                settings);
        registerGauges(hub, registry);
        return hub;
    }

    /** {@code pti_api_sse_connections{channel, audience}}, the buffer size and the consumer lag (DOC-26 §12). */
    private static void registerGauges(EventHub hub, MeterRegistry registry) {
        for (UiChannel channel : UiChannel.values()) {
            for (boolean authenticated : new boolean[] {false, true}) {
                Gauge.builder("pti.api.sse.connections", hub, h -> h.connectionCount(channel, authenticated))
                        .tag("channel", channel.wireName())
                        .tag("audience", authenticated ? "authenticated" : "public")
                        .register(registry);
            }
        }
        Gauge.builder("pti.api.sse.buffer.events", hub, EventHub::bufferSize).register(registry);
        Gauge.builder("pti.api.sse.consumer.lag", hub, h -> h.consumerLag().toMillis() / 1000.0)
                .baseUnit("seconds")
                .register(registry);
    }

    @Bean
    SseFrameWriter sseFrameWriter(JsonMapper mapper) {
        return new SseFrameWriter(mapper);
    }

    @Bean
    StreamEndpointSettings streamEndpointSettings(StreamProperties sse) {
        return new StreamEndpointSettings(sse.enabled(), sse.maxLifetime(), sse.maxRouteFilter());
    }

    @Bean
    SseShutdown sseShutdown(EventHub hub) {
        return new SseShutdown(hub);
    }

    @Bean
    StreamSchedules streamSchedules(EventHub hub) {
        return new StreamSchedules(hub);
    }

    @Bean
    @ConditionalOnProperty(name = "pti.api.sse.enabled", havingValue = "true", matchIfMissing = true)
    UiEventConsumer uiEventConsumer(
            EventHub hub, StreamMetrics metrics, BusinessClock clock, JsonMapper mapper, StreamProperties sse) {
        return new UiEventConsumer(hub, metrics, clock, mapper, sse.bufferWindow());
    }

    /**
     * DOC-26 §4.1: group {@code pti-api-sse-<host>}, no auto commit and no manual one either (the listener never
     * acknowledges), {@code latest} when a time seek finds nothing, 100 ms fetch wait, one thread for all partitions.
     */
    @Bean
    @ConditionalOnProperty(name = "pti.api.sse.enabled", havingValue = "true", matchIfMissing = true)
    ConcurrentMessageListenerContainer<String, String> uiEventListenerContainer(
            KafkaProperties kafka,
            UiEventConsumer consumer,
            @Value("${pti.kafka.topic-prefix:}") String topicPrefix,
            @Value("${HOSTNAME:}") String hostname) {
        Map<String, Object> props = kafka.buildConsumerProperties();
        String host = hostname.isBlank() ? UUID.randomUUID().toString() : hostname;
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "pti-api-sse-" + host);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        DefaultKafkaConsumerFactory<String, String> factory =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
        ContainerProperties container = new ContainerProperties(topicPrefix + EVENTS_UI_TOPIC);
        container.setMessageListener(consumer);
        container.setConsumerRebalanceListener(consumer);
        container.setAckMode(ContainerProperties.AckMode.MANUAL);
        container.setObservationEnabled(true);
        ConcurrentMessageListenerContainer<String, String> listener =
                new ConcurrentMessageListenerContainer<>(factory, container);
        listener.setConcurrency(1);
        listener.setBeanName("pti-api-sse");
        return listener;
    }
}
