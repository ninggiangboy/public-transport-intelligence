package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.analytics.core.domain.Trigger;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.EtlApplication;
import dev.pti.etl.EtlKafka;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The 30 second tick of DOC-23 §4.2, here every second: it advances routes that hold state, off the scheduler. */
@SpringBootTest(classes = EtlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"stream", "test"})
@Import(AnalyticsTickIT.Detectors.class)
class AnalyticsTickIT {

    private static final String PREFIX = EtlKafka.newPrefix();

    @TestConfiguration(proxyBeanMethods = false)
    static class Detectors {
        @Bean
        RecordingDetector recordingDetector() {
            RecordingDetector detector = new RecordingDetector();
            detector.needTick("18", "5");
            return detector;
        }
    }

    @Autowired
    RecordingDetector detector;

    @Autowired
    MeterRegistry meters;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        EtlKafka.createTopics(PREFIX);
        EtlKafka.createTopic(PREFIX, UiEventTopic.NAME, 3);
        registry.add("spring.kafka.bootstrap-servers", EtlKafka::bootstrapServers);
        registry.add("spring.datasource.url", () -> MigratedDatabases.jdbcUrl("pti_warehouse"));
        registry.add("spring.datasource.password", () -> MigratedDatabases.password("etl_writer"));
        registry.add("pti.kafka.topic-prefix", () -> PREFIX);
        registry.add("server.port", () -> "0");
        registry.add("management.server.port", () -> "0");
        registry.add("pti.analytics.dispatcher.tick-interval", () -> "1s");
    }

    @Test
    void theTickAdvancesEveryRouteThatNeedsOneOnTheAnalyticsExecutor() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(detector.advances())
                    .extracting(RecordingDetector.Advance::routeId)
                    .contains("18", "5");
            assertThat(detector.advances()).allSatisfy(advance -> {
                assertThat(advance.trigger()).isEqualTo(Trigger.TICK);
                assertThat(advance.thread()).startsWith("analytics-");
                assertThat(advance.context().sourceBatchId()).isNull();
            });
        });
        assertThat(meters.get("pti.analytics.runs")
                        .tags("detector", "bunching", "trigger", "tick", "outcome", "ok")
                        .counter()
                        .count())
                .isPositive();
    }

    @Test
    void theTickRefreshesTheOpenEpisodeGaugesFromTheDatabase() {
        // A gauge is registered with the first tick, so looking for it before that throws.
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertThat(meters.get("pti.analytics.open.episodes")
                            .tag("detector", "bunching")
                            .gauge()
                            .value())
                    .isNotNegative();
            assertThat(meters.get("pti.analytics.open.episodes")
                            .tag("detector", "disruption")
                            .gauge()
                            .value())
                    .isNotNegative();
        });
    }
}
