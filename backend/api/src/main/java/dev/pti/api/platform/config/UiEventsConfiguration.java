package dev.pti.api.platform.config;

import dev.pti.api.platform.adapter.out.kafka.KafkaUiEventPublisher;
import dev.pti.common.events.UiEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The producer of UI events (DOC-33 §2.1, DOC-09 §1.2): one {@link UiEventPublisher} for every feature that writes
 * and tells the screens about it. {@code acks=1}, no idempotence and {@code linger.ms=5}, because an event is best
 * effort and latency matters more than durability; the blocking limits are short, so a broker that is down holds a
 * request thread for seconds, not for the default minute.
 */
@Configuration(proxyBeanMethods = false)
class UiEventsConfiguration {

    static final String EVENTS_UI_TOPIC = "pti.events.ui";

    @Bean
    ProducerFactory<String, String> uiEventProducerFactory(KafkaProperties kafka, MeterRegistry meters) {
        Map<String, Object> props = kafka.buildProducerProperties();
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        DefaultKafkaProducerFactory<String, String> factory =
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer());
        factory.addListener(new MicrometerProducerListener<>(meters));
        return factory;
    }

    @Bean
    KafkaTemplate<String, String> uiEventKafkaTemplate(ProducerFactory<String, String> uiEventProducerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(uiEventProducerFactory);
        // The traceparent header links the UI event to the request that made it (DOC-09 §8).
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    UiEventPublisher uiEventPublisher(
            KafkaTemplate<String, String> uiEventKafkaTemplate,
            @Value("${pti.kafka.topic-prefix:}") String topicPrefix,
            MeterRegistry meters) {
        return new KafkaUiEventPublisher(uiEventKafkaTemplate, topicPrefix + EVENTS_UI_TOPIC, meters);
    }
}
