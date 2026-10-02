package dev.pti.etl.realtime.config;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.realtime.adapter.in.event.VehiclePositionsListener;
import dev.pti.etl.realtime.adapter.in.scheduling.VehiclesBatchSchedule;
import dev.pti.etl.realtime.adapter.out.kafka.KafkaRealtimeEventSink;
import dev.pti.etl.realtime.application.VehiclesBatchPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The live map feed of {@code etl-stream} (DOC-20 §8): VehiclePosition polls become one {@code vehicles.batch} per
 * route and second on {@code pti.events.ui}, through the UI event producer the analytics events use too.
 */
@Configuration(proxyBeanMethods = false)
@Profile("stream")
class RealtimeConfiguration {

    static final String EVENTS_UI_TOPIC = "pti.events.ui";

    @Bean
    VehiclesBatchPublisher vehiclesBatchPublisher(
            @Qualifier("uiEventKafkaTemplate") KafkaTemplate<String, String> template,
            @Value("${pti.kafka.topic-prefix:}") String topicPrefix,
            BusinessClock clock,
            MeterRegistry meters) {
        return new VehiclesBatchPublisher(
                new KafkaRealtimeEventSink(template, topicPrefix + EVENTS_UI_TOPIC, clock, meters), clock);
    }

    @Bean
    VehiclePositionsListener vehiclePositionsListener(VehiclesBatchPublisher publisher) {
        return new VehiclePositionsListener(publisher);
    }

    @Bean
    VehiclesBatchSchedule vehiclesBatchSchedule(VehiclesBatchPublisher publisher) {
        return new VehiclesBatchSchedule(publisher);
    }
}
