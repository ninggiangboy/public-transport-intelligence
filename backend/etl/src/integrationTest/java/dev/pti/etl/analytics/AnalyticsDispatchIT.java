package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.analytics.bunching.application.BunchingDetector;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.json.MessageJson;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.EtlApplication;
import dev.pti.etl.EtlKafka;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.stream.MicroBatchCommitted;
import dev.pti.etl.stream.StreamChunkResult;
import dev.pti.etl.write.WriteMode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * {@code etl-stream} with analytics on (DOC-23 §18.8): a committed micro-batch reaches the detectors on the analytics
 * executor, their events reach {@code pti.events.ui}, one failing run does not stop the next, and a full queue drops
 * the oldest entry and counts it (AN-I-01, AN-I-04, AN-I-05). The detector is a recording stand-in: the real ones
 * come with their own slices.
 */
@SpringBootTest(classes = EtlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"stream", "test"})
@Import(AnalyticsDispatchIT.Detectors.class)
class AnalyticsDispatchIT {

    private static final String PREFIX = EtlKafka.newPrefix();
    private static UiEventTopic topic;

    @TestConfiguration(proxyBeanMethods = false)
    static class Detectors {
        @Bean
        RecordingDetector recordingDetector() {
            return new RecordingDetector();
        }
    }

    @Autowired
    RecordingDetector detector;

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    MeterRegistry meters;

    @Autowired
    ThreadPoolTaskExecutor analyticsExecutor;

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
        registry.add("pti.analytics.dispatcher.tick-interval", () -> "1h");
        registry.add("pti.etl.analytics.executor.threads", () -> "1");
        registry.add("pti.etl.analytics.executor.queue-capacity", () -> "2");
    }

    @BeforeAll
    static void open() {
        // The Spring context, which would create the topic, is built with the first test, after this method.
        EtlKafka.createTopic(PREFIX, UiEventTopic.NAME, 3);
        topic = new UiEventTopic(PREFIX);
    }

    @AfterAll
    static void close() {
        topic.close();
    }

    @BeforeEach
    void reset() {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> analyticsExecutor.getActiveCount() == 0
                        && analyticsExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        detector.reset();
    }

    @AfterEach
    void drain() {
        // A test that blocked the detector released it already; wait until nothing of it is left running.
        await().atMost(Duration.ofSeconds(30))
                .until(() -> analyticsExecutor.getActiveCount() == 0
                        && analyticsExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private static MicroBatchCommitted committed(EtlSource source, UUID batchId, String... routes) {
        Instant now = Instant.now();
        StreamChunkResult result = new StreamChunkResult(
                batchId,
                source,
                WriteMode.BATCH,
                1,
                1,
                0,
                0,
                now.minusSeconds(4),
                now.minusSeconds(3),
                now.minusSeconds(5),
                Set.of(routes));
        return new MicroBatchCommitted(result, now);
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theBunchingDetectorIsAnAnalyticsBeanNextToTheStandIn() {
        // No etl change is needed for a detector: the dispatcher takes every RouteDetector bean (DOC-23 §4.1).
        assertThat(context.getBeansOfType(BunchingDetector.class)).hasSize(1);
    }

    @Test
    void aCommittedBatchIsAnalysedOnTheAnalyticsExecutorAndItsEventsReachTheTopic() {
        UUID batch = UUID.randomUUID();
        String episode = UUID.randomUUID().toString();
        detector.emit(
                "18",
                new InsightEvent(
                        "bunching.opened",
                        UiChannel.ALERTS,
                        Audience.OPERATIONS,
                        episode,
                        "18",
                        Instant.now().minusSeconds(5),
                        Instant.now(),
                        Map.of("id", episode, "routeId", "18")));

        events.publishEvent(committed(EtlSource.GTFS_RT_VEHICLE_POSITION, batch, "18"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(detector.advances()).hasSize(1);
            RecordingDetector.Advance advance = detector.advances().getFirst();
            assertThat(advance.routeId()).isEqualTo("18");
            assertThat(advance.trigger()).isEqualTo(Trigger.BATCH);
            assertThat(advance.thread()).startsWith("analytics-");
            assertThat(advance.context().sourceBatchId()).isEqualTo(batch);
        });
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(topic.polled(r -> episode.equals(r.key()))).hasSize(1);
            ConsumerRecord<String, String> record =
                    topic.polled(r -> episode.equals(r.key())).getFirst();
            JsonNode envelope = MessageJson.mapper().readTree(record.value());
            assertThat(envelope.path("type").asString()).isEqualTo("bunching.opened");
            assertThat(envelope.path("channel").asString()).isEqualTo("alerts");
            assertThat(envelope.path("route_id").asString()).isEqualTo("18");
        });
        assertThat(count("pti.analytics.runs", "detector", "bunching", "trigger", "batch", "outcome", "ok"))
                .isPositive();
        assertThat(meters.get("pti.analytics.dispatch.delay").timer().count()).isPositive();
        assertThat(meters.get("pti.ui.commit.to.publish")
                        .tag("type", "bunching.opened")
                        .timer()
                        .count())
                .isPositive();
    }

    @Test
    void publishingTheEventDoesNotWaitForTheAnalytics() {
        CountDownLatch gate = detector.blockAdvances();
        try {
            long start = System.nanoTime();
            events.publishEvent(committed(EtlSource.GTFS_RT_VEHICLE_POSITION, UUID.randomUUID(), "18"));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took).as("the Kafka listener thread is not held").isLessThan(Duration.ofSeconds(2));
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> detector.advances().size() == 1);
        } finally {
            gate.countDown();
        }
    }

    @Test
    void oneRunThatFailsDoesNotStopTheNextRouteAndIsCounted() {
        double errorsBefore =
                count("pti.analytics.runs", "detector", "bunching", "trigger", "batch", "outcome", "error");
        detector.failOn("18");

        events.publishEvent(committed(EtlSource.GTFS_RT_VEHICLE_POSITION, UUID.randomUUID(), "18", "5"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(detector.advances())
                    .extracting(RecordingDetector.Advance::routeId)
                    .containsExactly("18", "5");
            assertThat(count("pti.analytics.runs", "detector", "bunching", "trigger", "batch", "outcome", "error"))
                    .isEqualTo(errorsBefore + 1);
        });
    }

    @Test
    void aTicketingBatchTriggersNoAnalytics() {
        events.publishEvent(committed(EtlSource.TICKETING_SALES, UUID.randomUUID(), "18"));
        events.publishEvent(committed(EtlSource.GTFS_RT_VEHICLE_POSITION, UUID.randomUUID(), "5"));

        await().atMost(Duration.ofSeconds(20)).until(() -> !detector.advances().isEmpty());
        assertThat(detector.advances())
                .extracting(RecordingDetector.Advance::routeId)
                .containsExactly("5");
    }

    @Test
    void aFullQueueDropsTheOldestEntriesAndCountsThem() {
        double dropsBefore = count("pti.analytics.dropped");
        CountDownLatch gate = detector.blockAdvances();
        try {
            // One thread is busy, two entries wait, the rest are dropped.
            for (int i = 0; i < 8; i++) {
                events.publishEvent(committed(EtlSource.GTFS_RT_VEHICLE_POSITION, UUID.randomUUID(), "18"));
            }

            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> assertThat(count("pti.analytics.dropped")).isGreaterThan(dropsBefore));
        } finally {
            gate.countDown();
        }
    }
}
