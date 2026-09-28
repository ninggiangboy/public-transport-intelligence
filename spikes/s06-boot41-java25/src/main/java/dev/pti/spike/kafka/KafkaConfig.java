package dev.pti.spike.kafka;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.SerializationFeature;

@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    /** DOC-20 §5: back off by pausing the container instead of sleeping in the consumer thread. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaListenerEndpointRegistry registry) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("kafka-pause-");
        scheduler.initialize();
        var pauser = new ContainerPausingBackOffHandler(new ListenerContainerPauseService(registry, scheduler));
        return new DefaultErrorHandler(null, new FixedBackOff(1000L, 3L), pauser);
    }

    /** DOC-11 §4: Boot 4 customizes the Jackson 3 JsonMapper builder. */
    @Bean
    JsonMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }
}
