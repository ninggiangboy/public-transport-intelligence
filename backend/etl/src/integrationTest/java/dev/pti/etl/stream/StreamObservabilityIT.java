package dev.pti.etl.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.common.json.MessageJson;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.EtlApplication;
import dev.pti.etl.EtlKafka;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.testing.EtlFixtures;
import dev.pti.testing.MetricCatalog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * {@code etl-stream} with tracing on and export off (DOC-28 §9): O-01 and O-02 against the metric catalog, and O-05,
 * a log line inside a chunk carries both {@code batch_id} and the trace id.
 */
@SpringBootTest(classes = EtlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"stream", "test"})
@ExtendWith(OutputCaptureExtension.class)
class StreamObservabilityIT {

    private static final String PREFIX = EtlKafka.newPrefix();
    private static final MetricCatalog CATALOG = MetricCatalog.load("metric-catalog-stream.txt");
    private static KafkaProducer<String, byte[]> producer;

    @LocalManagementPort
    int managementPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        EtlKafka.createTopics(PREFIX);
        registry.add("spring.kafka.bootstrap-servers", EtlKafka::bootstrapServers);
        registry.add("spring.datasource.url", () -> MigratedDatabases.jdbcUrl("pti_warehouse"));
        registry.add("spring.datasource.password", () -> MigratedDatabases.password("etl_writer"));
        registry.add("pti.kafka.topic-prefix", () -> PREFIX);
        registry.add("management.tracing.export.enabled", () -> "false");
        registry.add("server.port", () -> "0");
        registry.add("management.server.port", () -> "0");
    }

    @BeforeAll
    static void open() {
        producer = EtlKafka.producer();
    }

    @AfterAll
    static void close() {
        producer.close();
    }

    @Test
    void metricsLogsAndTracesOfOnePoll(CapturedOutput output) throws Exception {
        assertThat(CATALOG.missing(scrape(), false)).as("registered at startup").isEmpty();

        String topic = PREFIX + EtlSource.TICKETING_SALES.requireTopic();
        String transaction = UUID.randomUUID().toString();
        EtlKafka.send(
                producer,
                topic,
                transaction,
                MessageJson.mapper()
                        .writeValueAsString(EtlFixtures.ticketSale().put("transaction_id", transaction))
                        .getBytes(StandardCharsets.UTF_8));
        EtlKafka.send(producer, topic, "bad-" + transaction, "{\"not\": json".getBytes(StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(scrape())
                        .contains("pti_etl_records_total")
                        .contains("pti_dlq_records_total")
                        .contains("pti_errors_total{")
                        .contains("pti_etl_kafka_to_commit_seconds_bucket")
                        .contains("pti_etl_chunk_duration_seconds_bucket"));
        String scrape = scrape();
        assertThat(CATALOG.violations(scrape)).as("labels").isEmpty();
        assertThat(scrape).containsPattern("pti_errors_total\\{[^}]*kind=\"data\"");

        String chunkLine = output.getOut()
                .lines()
                .filter(l -> l.contains("Chunk committed") && l.contains("batch_id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No 'Chunk committed' log line"));
        JsonNode line = MessageJson.mapper().readTree(chunkLine);
        assertThat(line.path("batch_id").asString()).as(chunkLine).isNotEmpty();
        assertThat(traceId(line)).as(chunkLine).matches("[0-9a-f]{32}");
    }

    /** ECS nests it as {@code trace.id}; accept the flat MDC key too. */
    private static String traceId(JsonNode line) {
        String nested = line.path("trace").path("id").asString("");
        return nested.isEmpty() ? line.path("traceId").asString("") : nested;
    }

    private String scrape() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(
                            HttpRequest.newBuilder(
                                            URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
        }
    }
}
