package dev.pti.etl.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.common.json.MessageJson;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.EtlApplication;
import dev.pti.etl.EtlKafka;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.testing.EtlFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ObjectNode;

/** {@code etl-stream} end to end for ticketing: Kafka in, warehouse out (DOC-20 §14 S-06, S-17). */
@SpringBootTest(classes = EtlApplication.class)
@ActiveProfiles({"stream", "test"})
class StreamTicketingIT {

    private static final String PREFIX = EtlKafka.newPrefix();
    private static KafkaProducer<String, byte[]> producer;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        EtlKafka.createTopics(PREFIX);
        registry.add("spring.kafka.bootstrap-servers", EtlKafka::bootstrapServers);
        registry.add("spring.datasource.url", () -> MigratedDatabases.jdbcUrl("pti_warehouse"));
        registry.add("spring.datasource.password", () -> MigratedDatabases.password("etl_writer"));
        registry.add("pti.kafka.topic-prefix", () -> PREFIX);
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

    private static void send(EtlSource source, String key, byte[] value) {
        EtlKafka.send(producer, PREFIX + source.requireTopic(), key, value);
    }

    private static byte[] json(ObjectNode node) {
        return MessageJson.mapper().writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void s06AnInferredSalePointIsReplacedByItsCdcRow() {
        String salePoint =
                "KIOSK-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        String transaction = UUID.randomUUID().toString();
        ObjectNode sale = EtlFixtures.ticketSale();
        sale.put("transaction_id", transaction).put("sale_point_id", salePoint);
        send(EtlSource.TICKETING_SALES, transaction, json(sale));

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(jdbc.queryForList(
                                "SELECT source FROM dw.dim_sale_point WHERE sale_point_id = ?",
                                String.class,
                                salePoint))
                        .containsExactly("INFERRED"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM dw.fact_ticket_sales WHERE transaction_id = ?::uuid",
                        Long.class,
                        transaction))
                .isEqualTo(1);

        ObjectNode point = EtlFixtures.salePoint();
        point.put("sale_point_id", salePoint);
        send(EtlSource.TICKETING_SALE_POINTS, salePoint, json(point));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbc.queryForList(
                                "SELECT source FROM dw.dim_sale_point WHERE sale_point_id = ?",
                                String.class,
                                salePoint))
                        .containsExactly("CDC"));
    }

    @Test
    void badBytesGoToTheDeadLetterQueueAndATombstoneIsOnlyCounted() {
        String key = "bad-" + UUID.randomUUID();
        send(EtlSource.TICKETING_SALES, key, new byte[] {'{', (byte) 0xFF, 0, '}'});
        send(EtlSource.TICKETING_SALES, key + "-tomb", null);

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(jdbc.queryForList(
                                "SELECT stage FROM ops.dead_letter WHERE kafka_topic = ? AND raw_payload LIKE '{%'",
                                String.class, PREFIX + EtlSource.TICKETING_SALES.requireTopic()))
                        .containsExactly("DESERIALIZE"));
        assertThat(jdbc.queryForObject(
                        "SELECT sum(records_read) FROM ops.etl_stream_batch WHERE source = 'TICKETING_SALES'"
                                + " AND offsets::text LIKE ?",
                        Long.class,
                        "%" + PREFIX + "%"))
                .isGreaterThanOrEqualTo(2L);
    }
}
