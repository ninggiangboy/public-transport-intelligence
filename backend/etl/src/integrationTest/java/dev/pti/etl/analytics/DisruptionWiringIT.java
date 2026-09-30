package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.EtlApplication;
import dev.pti.etl.EtlKafka;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The disruption detector as {@code etl-stream} wires it (DOC-23 §4, P4-04): the scan of {@code dev.pti.analytics}
 * finds it, the dispatcher and the tick see it as a {@link RouteDetector}, and the tick advances a route that holds
 * state. No feed is loaded here, so the run ends in {@code noop}; the detection itself is in the analytics module's
 * integration test.
 */
@SpringBootTest(classes = EtlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"stream", "test"})
class DisruptionWiringIT {

    private static final String PREFIX = EtlKafka.newPrefix();
    private static final String ROUTE =
            "AN3-WIRING-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    List<RouteDetector> detectors;

    @Autowired
    JdbcClient jdbc;

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

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM insight.analytics_route_baseline WHERE route_id = :route")
                .param("route", ROUTE)
                .update();
    }

    @Test
    void theDetectorIsABeanAndTheTickAdvancesARouteThatHoldsState() {
        assertThat(detectors).extracting(RouteDetector::detector).contains(Detector.DISRUPTION);
        jdbc.sql("""
                INSERT INTO insight.analytics_route_baseline (route_id, direction_id, ewma_mean, ewma_var,
                                                              bucket_count, last_bucket, consecutive_high)
                VALUES (:route, 0, 63, 891, 61, now(), 1)""").param("route", ROUTE).update();

        await().atMost(Duration.ofSeconds(30))
                .ignoreExceptions()
                .untilAsserted(() -> assertThat(meters.get("pti.analytics.runs")
                                .tags("detector", "disruption", "trigger", "tick", "outcome", "noop")
                                .counter()
                                .count())
                        .isPositive());
    }
}
