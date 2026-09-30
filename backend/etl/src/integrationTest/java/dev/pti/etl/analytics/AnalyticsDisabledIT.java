package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.EtlApplication;
import dev.pti.etl.EtlKafka;
import dev.pti.etl.analytics.adapter.in.event.AnalyticsDispatcher;
import dev.pti.etl.analytics.adapter.in.scheduling.AnalyticsTick;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.stream.MicroBatchCommitted;
import dev.pti.etl.stream.StreamChunkResult;
import dev.pti.etl.write.WriteMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code pti.analytics.enabled=false}, which is how the baseline container runs (DOC-23 §2.6, AN-I-06): there is no
 * dispatcher, no tick and no analytics executor, while the event sink and the reference cache are still available.
 */
@SpringBootTest(classes = EtlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"stream", "test"})
@Import(AnalyticsDisabledIT.Detectors.class)
class AnalyticsDisabledIT {

    private static final String PREFIX = EtlKafka.newPrefix();

    @TestConfiguration(proxyBeanMethods = false)
    static class Detectors {
        @Bean
        RecordingDetector recordingDetector() {
            return new RecordingDetector();
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    RecordingDetector detector;

    @Autowired
    ApplicationEventPublisher events;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        EtlKafka.createTopics(PREFIX);
        registry.add("spring.kafka.bootstrap-servers", EtlKafka::bootstrapServers);
        registry.add("spring.datasource.url", () -> MigratedDatabases.jdbcUrl("pti_warehouse"));
        registry.add("spring.datasource.password", () -> MigratedDatabases.password("etl_writer"));
        registry.add("pti.kafka.topic-prefix", () -> PREFIX);
        registry.add("server.port", () -> "0");
        registry.add("management.server.port", () -> "0");
        registry.add("pti.analytics.enabled", () -> "false");
        registry.add("pti.analytics.dispatcher.tick-interval", () -> "1s");
    }

    @Test
    void thereIsNoDispatcherTickOrExecutor() {
        assertThat(context.getBeansOfType(AnalyticsDispatcher.class)).isEmpty();
        assertThat(context.getBeansOfType(AnalyticsTick.class)).isEmpty();
        assertThat(context.containsBean("analyticsExecutor")).isFalse();
    }

    @Test
    void theSinkAndTheReferenceCacheAreStillThere() {
        assertThat(context.getBeansOfType(AnalyticsEventSink.class)).hasSize(1);
        assertThat(context.getBeansOfType(AnalyticsReferenceCache.class)).hasSize(1);
    }

    @Test
    void aCommittedBatchAndTheTickReachNoDetector() {
        events.publishEvent(new MicroBatchCommitted(
                new StreamChunkResult(
                        UUID.randomUUID(),
                        EtlSource.GTFS_RT_VEHICLE_POSITION,
                        WriteMode.BATCH,
                        1,
                        1,
                        0,
                        0,
                        Instant.now(),
                        Instant.now(),
                        Instant.now(),
                        Set.of("18")),
                Instant.now()));
        detector.needTick("18");

        // The tick is set to one second: several would have run by now.
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .until(() -> detector.advances().isEmpty());
    }
}
