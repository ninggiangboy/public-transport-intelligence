package dev.pti.etl.analytics.config;

import dev.pti.analytics.reference.application.port.ActiveFeedVersion;
import dev.pti.common.spring.SpringTransactionRunner;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import dev.pti.etl.analytics.adapter.out.feed.ReferenceDataActiveFeed;
import dev.pti.etl.analytics.adapter.out.kafka.KafkaAnalyticsEventSink;
import dev.pti.etl.config.PlatformProperties;
import dev.pti.etl.reference.ReferenceDataHolder;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;

/**
 * Connects the {@code analytics} library to {@code etl} in both profiles (DOC-23 §1.1): the library's own
 * configuration is picked up by scanning {@code dev.pti.analytics}; this class adds what the library expects from its
 * host: the transaction runner, the ACTIVE feed version, and the sink that sends UI events to Kafka.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan("dev.pti.analytics")
@EnableConfigurationProperties(AnalyticsExecutorProperties.class)
class EtlAnalyticsConfiguration {

    static final String EVENTS_UI_TOPIC = "pti.events.ui";

    /** One transaction per route and detector, READ COMMITTED (DOC-23 §12.1). */
    @Bean
    TransactionRunner analyticsTransactionRunner(PlatformTransactionManager transactionManager) {
        return new SpringTransactionRunner(transactionManager).isolation(Isolation.READ_COMMITTED);
    }

    @Bean
    ActiveFeedVersion activeFeedVersion(ReferenceDataHolder holder) {
        return new ReferenceDataActiveFeed(holder);
    }

    /**
     * The producer for UI events (DOC-09 §1.2): {@code acks=1}, no idempotence, {@code linger.ms=5}, because an event
     * is best-effort and latency matters more than durability. The blocking limits are short, so that a broker that
     * is down holds an analytics thread for seconds and not for the default minute.
     */
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
        // The traceparent header links the UI event to the run that made it (DOC-09 §8).
        template.setObservationEnabled(true);
        return template;
    }

    /** Available in both profiles: the stream runs analytics after a micro-batch, the batch jobs will too. */
    @Bean
    KafkaAnalyticsEventSink analyticsEventSink(
            KafkaTemplate<String, String> uiEventKafkaTemplate,
            PlatformProperties platform,
            BusinessClock clock,
            MeterRegistry meters) {
        return new KafkaAnalyticsEventSink(
                uiEventKafkaTemplate, platform.kafka().topicPrefix() + EVENTS_UI_TOPIC, clock, meters);
    }
}
