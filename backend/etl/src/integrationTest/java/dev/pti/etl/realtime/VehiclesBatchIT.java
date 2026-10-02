package dev.pti.etl.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.common.json.MessageJson;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.EtlApplication;
import dev.pti.etl.EtlKafka;
import dev.pti.etl.analytics.UiEventTopic;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.stream.MicroBatchCommitted;
import dev.pti.etl.stream.StreamChunkResult;
import dev.pti.etl.write.WriteMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * {@code etl-stream} turns a committed VehiclePosition poll into {@code vehicles.batch} on {@code pti.events.ui}
 * (DOC-20 §8, DOC-33 §5.1): one event per route within the next second, keyed by route, with the positions and the
 * record time of the poll as {@code source_record_ts}.
 */
@SpringBootTest(classes = EtlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"stream", "test"})
class VehiclesBatchIT {

    private static final String PREFIX = EtlKafka.newPrefix();
    private static UiEventTopic topic;

    @Autowired
    ApplicationEventPublisher events;

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
    }

    @BeforeAll
    static void open() {
        EtlKafka.createTopic(PREFIX, UiEventTopic.NAME, 3);
        topic = new UiEventTopic(PREFIX);
    }

    @AfterAll
    static void close() {
        topic.close();
    }

    private static VehiclePositionRow row(String route, String vehicle, Instant at) {
        return new VehiclePositionRow(
                LocalDate.parse("2026-09-29"),
                vehicle,
                at,
                "trip-" + vehicle,
                route,
                (short) 0,
                44.948121,
                -93.278004,
                358.0f,
                7.4f,
                14,
                "51420",
                "IN_TRANSIT_TO",
                "MANY_SEATS_AVAILABLE",
                (short) 2,
                "hash");
    }

    @Test
    void aCommittedPollBecomesOneVehiclesBatchPerRoute() {
        Instant at = Instant.parse("2026-09-29T21:19:30Z");
        Instant record = Instant.parse("2026-09-29T21:19:30.500Z");
        String route = "VB-" + UUID.randomUUID().toString().substring(0, 6);
        StreamChunkResult result = new StreamChunkResult(
                UUID.randomUUID(),
                EtlSource.GTFS_RT_VEHICLE_POSITION,
                WriteMode.BATCH,
                2,
                2,
                0,
                0,
                at,
                at,
                record,
                Set.of(route),
                List.of(row(route, "1203", at), row(route, "1187", at)));

        events.publishEvent(new MicroBatchCommitted(result, Instant.now()));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> !topic.polled(r -> route.equals(r.key())).isEmpty());
        List<ConsumerRecord<String, String>> sent = topic.polled(r -> route.equals(r.key()));
        assertThat(sent).hasSize(1);
        JsonNode envelope = MessageJson.mapper().readTree(sent.getFirst().value());
        assertThat(envelope.path("type").asString()).isEqualTo("vehicles.batch");
        assertThat(envelope.path("channel").asString()).isEqualTo("vehicles");
        assertThat(envelope.path("audience").asString()).isEqualTo("PUBLIC");
        assertThat(envelope.path("route_id").asString()).isEqualTo(route);
        assertThat(envelope.path("source_record_ts").asString()).isEqualTo("2026-09-29T21:19:30.500Z");
        assertThat(envelope.at("/data/vehicles")).hasSize(2);
        assertThat(envelope.at("/data/vehicles/0/vehicleId").asString()).isEqualTo("1203");
        assertThat(envelope.at("/data/vehicles/0/eventTimestamp").asString()).isEqualTo("2026-09-29T21:19:30Z");
        assertThat(envelope.at("/data/vehicles/0/occupancyStatus").asString()).isEqualTo("MANY_SEATS_AVAILABLE");
    }
}
