package dev.pti.etl.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.exception.NotFoundException;
import dev.pti.common.error.DataException;
import dev.pti.common.json.MessageJson;
import dev.pti.common.pii.PiiScrubber;
import dev.pti.db.PostgresFixtures;
import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.core.cdc.CdcReader;
import dev.pti.etl.core.cdc.SalePointCdcProcessor;
import dev.pti.etl.core.cdc.TicketSaleCdcProcessor;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.rules.RuleEngine;
import dev.pti.etl.rules.TicketRules;
import dev.pti.etl.testing.EtlFixtures;
import dev.pti.etl.write.DeadLetter;
import dev.pti.etl.write.DedupRegistry;
import dev.pti.etl.write.FactChunkWriter;
import dev.pti.etl.write.JdbcDeadLetterWriter;
import dev.pti.etl.write.KnownKeyCache;
import dev.pti.etl.write.RefundRule;
import dev.pti.etl.write.RunMode;
import dev.pti.etl.write.WriteContext;
import dev.pti.etl.write.WriteStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * DOC-44 §9.2, K-01…K-08: the real Debezium connector, registered from {@code deploy/connect/connectors}, against the
 * migrated ticketing source, and its messages through the ETL processors and writer into a migrated warehouse.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DebeziumTicketingContractTest {

    private static final Path REPO = Path.of(System.getProperty("pti.repo-root", "../.."));
    private static final String SALES = "ticketing.sales.cdc";
    private static final String SALE_POINTS = "ticketing.sale_points.cdc";

    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer SOURCE =
            PostgresFixtures.source().withNetwork(NETWORK).withNetworkAliases("pg-source");
    private static final PostgreSQLContainer WAREHOUSE = PostgresFixtures.warehouse();
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");
    private static GenericContainer<?> connect;

    private static JdbcTemplate source;
    private static JdbcTemplate owner;
    private static JdbcTemplate warehouse;
    private static TransactionTemplate tx;
    private static FactChunkWriter writer;
    private static JdbcDeadLetterWriter deadLetters;
    private static KafkaConsumer<String, byte[]> consumer;
    private static final List<ConsumerRecord<String, byte[]>> RECEIVED = new ArrayList<>();
    private static final List<String> SNAPSHOT_SALES = new ArrayList<>();

    private static final DqProperties DQ = EtlFixtures.dq();
    private static final CdcReader CDC =
            new CdcReader(Validation.buildDefaultValidatorFactory().getValidator());
    private static final Map<String, MessageProcessor> PROCESSORS = Map.of(
            SALES,
            new TicketSaleCdcProcessor(CDC, new RuleEngine<>(TicketRules.all(DQ), DQ), EtlFixtures.AGENCY_ZONE),
            SALE_POINTS,
            new SalePointCdcProcessor(CDC));

    @BeforeAll
    static void start() throws Exception {
        SOURCE.start();
        WAREHOUSE.start();
        KAFKA.start();
        PostgresFixtures.migrate(WAREHOUSE, SOURCE);
        source = jdbc(SOURCE, "ticketing_source", "ticketing_owner");
        owner = source;
        warehouse = jdbc(WAREHOUSE, "pti_warehouse", "etl_writer");
        tx = new TransactionTemplate(new DataSourceTransactionManager(warehouse.getDataSource()));
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(warehouse);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        deadLetters = new JdbcDeadLetterWriter(named, PiiScrubber.withDefaults(), 1_048_576, meters);
        writer = new FactChunkWriter(
                named,
                new RefundRule(warehouse, DQ),
                new DedupRegistry(warehouse),
                false,
                deadLetters,
                new KnownKeyCache(1000),
                new WriteStats(meters),
                DQ);

        seed();
        connect = connectContainer()
                .withNetwork(NETWORK)
                .withExposedPorts(8083)
                .withEnv(Map.ofEntries(
                        Map.entry("BOOTSTRAP_SERVERS", "kafka:19092"),
                        Map.entry("GROUP_ID", "pti-connect"),
                        Map.entry("CONFIG_STORAGE_TOPIC", "connect-configs"),
                        Map.entry("OFFSET_STORAGE_TOPIC", "connect-offsets"),
                        Map.entry("STATUS_STORAGE_TOPIC", "connect-status"),
                        Map.entry("CONFIG_STORAGE_REPLICATION_FACTOR", "1"),
                        Map.entry("OFFSET_STORAGE_REPLICATION_FACTOR", "1"),
                        Map.entry("STATUS_STORAGE_REPLICATION_FACTOR", "1"),
                        Map.entry("KEY_CONVERTER", "org.apache.kafka.connect.json.JsonConverter"),
                        Map.entry("VALUE_CONVERTER", "org.apache.kafka.connect.json.JsonConverter"),
                        Map.entry("CONNECT_CONFIG_PROVIDERS", "env"),
                        Map.entry(
                                "CONNECT_CONFIG_PROVIDERS_ENV_CLASS",
                                "org.apache.kafka.common.config.provider.EnvVarConfigProvider"),
                        Map.entry("CONNECT_CONFIG_PROVIDERS_ENV_PARAM_ALLOWLIST_PATTERN", "^DEBEZIUM_PASSWORD$"),
                        Map.entry("CONNECT_OFFSET_FLUSH_INTERVAL_MS", "1000"),
                        Map.entry("DEBEZIUM_PASSWORD", PostgresFixtures.PASSWORDS.get("DEBEZIUM_PASSWORD"))))
                .waitingFor(Wait.forHttp("/connectors").forPort(8083).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(5));
        connect.start();
        register();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "contract-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Debezium creates the topics after the subscription; find them within a second, not five minutes.
        props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "1000");
        consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(java.util.regex.Pattern.compile("ticketing\\.sale.*"));
    }

    /**
     * The image {@code make images} built from {@code deploy/connect/Dockerfile} when it is on this machine (the build
     * downloads the S3 sink from GitHub, which is slow); otherwise the same Dockerfile is built here.
     */
    private static GenericContainer<?> connectContainer() {
        String local = System.getProperty("pti.connect.image", "ghcr.io/ninggiangboy/pti-connect:local");
        try {
            DockerClientFactory.instance().client().inspectImageCmd(local).exec();
            return new GenericContainer<>(DockerImageName.parse(local));
        } catch (NotFoundException e) {
            return new GenericContainer<>(new ImageFromDockerfile("pti-connect-contract", false)
                    .withDockerfile(REPO.resolve("deploy/connect/Dockerfile")));
        }
    }

    @AfterAll
    static void stop() {
        if (consumer != null) {
            consumer.close();
        }
        if (connect != null) {
            connect.stop();
        }
        KAFKA.stop();
        SOURCE.stop();
        WAREHOUSE.stop();
        NETWORK.close();
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer container, String database, String role) {
        return new JdbcTemplate(new SingleConnectionDataSource(
                PostgresFixtures.jdbcUrl(container, database),
                role,
                PostgresFixtures.PASSWORDS.get(role.toUpperCase(java.util.Locale.ROOT) + "_PASSWORD"),
                true));
    }

    /** K-01 data: sale points and 100 transactions that exist before the connector is registered. */
    private static void seed() {
        source.update(
                "INSERT INTO sale_point (sale_point_id, name, kind, stop_id) VALUES ('KIOSK-001', 'Nicollet', 'KIOSK', '51631')");
        source.update("INSERT INTO sale_point (sale_point_id, name, kind) VALUES ('APP-IOS', 'iOS app', 'APP')");
        source.update(
                "INSERT INTO sale_point (sale_point_id, name, kind, stop_id) VALUES ('KIOSK-DEL', 'Closed', 'KIOSK', '51405')");
        for (int i = 0; i < 100; i++) {
            String id = UUID.randomUUID().toString();
            SNAPSHOT_SALES.add(id);
            source.update("""
                    INSERT INTO ticket_transaction (transaction_id, sale_point_id, ticket_type, txn_type, amount,
                                                    customer_ref)
                    VALUES (?::uuid, 'KIOSK-001', 'SINGLE', 'SALE', 2.50, 'cust-seed')
                    """, id);
        }
    }

    private static void register() throws IOException, InterruptedException {
        String config = Files.readString(REPO.resolve("deploy/connect/connectors/debezium-ticketing.json"));
        URI uri = URI.create("http://" + connect.getHost() + ":" + connect.getMappedPort(8083) + "/connectors");
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(uri)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(config))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    /** Polls until {@code done} holds for what arrived so far; returns the records that arrived meanwhile. */
    private static List<ConsumerRecord<String, byte[]>> await(Predicate<List<ConsumerRecord<String, byte[]>>> done) {
        List<ConsumerRecord<String, byte[]>> fresh = new ArrayList<>();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (!done.test(fresh)) {
            assertThat(Instant.now())
                    .as("records did not arrive; got " + fresh.size())
                    .isBefore(deadline);
            consumer.poll(Duration.ofMillis(500)).forEach(fresh::add);
        }
        RECEIVED.addAll(fresh);
        return fresh;
    }

    private static JsonNode json(ConsumerRecord<String, byte[]> record) {
        return MessageJson.mapper().readTree(record.value());
    }

    private static long count(List<ConsumerRecord<String, byte[]>> records, String topic, Predicate<JsonNode> test) {
        return records.stream()
                .filter(r -> r.topic().equals(topic) && r.value() != null && test.test(json(r)))
                .count();
    }

    /** Runs records through the processors and the writer, as one chunk; returns the data errors by business id. */
    private static Map<String, DataException> etl(List<ConsumerRecord<String, byte[]>> records) {
        Map<String, DataException> rejected = new HashMap<>();
        List<WriteSet> sets = new ArrayList<>();
        UUID batchId = UUID.randomUUID();
        RuleContext context = new RuleContext(Instant.now(), false, null);
        tx.executeWithoutResult(status -> {
            for (ConsumerRecord<String, byte[]> r : records) {
                if (r.value() == null) {
                    continue;
                }
                MessageProcessor processor = PROCESSORS.get(r.topic());
                InboundMessage message = new InboundMessage(
                        processor.source(),
                        r.key(),
                        r.value(),
                        r.topic(),
                        r.partition(),
                        r.offset(),
                        Instant.ofEpochMilli(r.timestamp()),
                        Map.of());
                try {
                    sets.add(processor.process(message, context));
                } catch (DataException e) {
                    rejected.put(
                            json(r).path(r.topic().equals(SALES) ? "transaction_id" : "sale_point_id")
                                    .asString(),
                            e);
                    deadLetters.write(DeadLetter.of(message, e, processor.businessKey(message), batchId));
                }
            }
            writer.write(sets, new WriteContext(batchId, RunMode.STREAM, false, Instant.now()));
        });
        return rejected;
    }

    @Test
    @Order(1)
    void k01TheSnapshotArrivesAsReadsAndLoads() {
        List<ConsumerRecord<String, byte[]>> snapshot =
                await(got -> count(got, SALES, j -> true) >= 100 && count(got, SALE_POINTS, j -> true) >= 3);

        assertThat(count(snapshot, SALES, j -> j.path("__op").asString().equals("r")))
                .isEqualTo(100);
        assertThat(etl(snapshot)).isEmpty();
        assertThat(warehouse.queryForObject(
                        "SELECT count(*) FROM dw.fact_ticket_sales WHERE transaction_id::text = ANY (?::text[])",
                        Long.class,
                        "{" + String.join(",", SNAPSHOT_SALES) + "}"))
                .isEqualTo(100);
    }

    private static List<ConsumerRecord<String, byte[]>> change(String topic, String key, String value, Runnable sql) {
        sql.run();
        return await(got -> count(got, topic, j -> j.path(key).asString().equals(value)) > 0);
    }

    @Test
    @Order(2)
    void k02AnInsertedSaleIsACreate() {
        String id = UUID.randomUUID().toString();
        List<ConsumerRecord<String, byte[]>> got = change(
                SALES,
                "transaction_id",
                id,
                () -> source.update(
                        "INSERT INTO ticket_transaction (transaction_id, sale_point_id, ticket_type, txn_type, amount) "
                                + "VALUES (?::uuid, 'APP-IOS', 'DAY', 'SALE', 5.00)",
                        id));

        assertThat(count(got, SALES, j -> j.path("__op").asString().equals("c")))
                .isEqualTo(1);
        assertThat(etl(got)).isEmpty();
        assertThat(warehouse.queryForObject(
                        "SELECT count(*) FROM dw.fact_ticket_sales WHERE transaction_id = ?::uuid", Long.class, id))
                .isEqualTo(1);
    }

    @Test
    @Order(3)
    void k03AnUpdatedSalePointIsAnUpdate() {
        List<ConsumerRecord<String, byte[]>> got = change(
                SALE_POINTS,
                "name",
                "Nicollet & Lake",
                () -> source.update(
                        "UPDATE sale_point SET name = 'Nicollet & Lake' WHERE sale_point_id = 'KIOSK-001'"));

        assertThat(count(got, SALE_POINTS, j -> j.path("__op").asString().equals("u")))
                .isEqualTo(1);
        assertThat(etl(got)).isEmpty();
        assertThat(warehouse.queryForObject(
                        "SELECT name FROM dw.dim_sale_point WHERE sale_point_id = 'KIOSK-001'", String.class))
                .isEqualTo("Nicollet & Lake");
    }

    @Test
    @Order(4)
    void k04ADeletedSalePointIsADeleteWithoutTombstone() {
        List<ConsumerRecord<String, byte[]>> got = change(
                SALE_POINTS,
                "__op",
                "d",
                () -> source.update("DELETE FROM sale_point WHERE sale_point_id = 'KIOSK-DEL'"));

        assertThat(got).noneMatch(r -> r.topic().equals(SALE_POINTS) && r.value() == null);
        assertThat(count(got, SALE_POINTS, j -> j.path("__deleted").asString().equals("true")))
                .isEqualTo(1);
        assertThat(etl(got)).isEmpty();
        assertThat(warehouse.queryForObject(
                        "SELECT is_deleted FROM dw.dim_sale_point WHERE sale_point_id = 'KIOSK-DEL'", Boolean.class))
                .isTrue();
    }

    @Test
    @Order(5)
    void k05ARefundLinksToItsSale() {
        String original = SNAPSHOT_SALES.getFirst();
        String refund = UUID.randomUUID().toString();
        List<ConsumerRecord<String, byte[]>> got = change(
                SALES,
                "transaction_id",
                refund,
                () -> source.update(
                        "INSERT INTO ticket_transaction (transaction_id, sale_point_id, ticket_type, txn_type, amount, refund_of)"
                                + " VALUES (?::uuid, 'KIOSK-001', 'SINGLE', 'REFUND', 2.50, ?::uuid)",
                        refund,
                        original));

        assertThat(etl(got)).isEmpty();
        assertThat(warehouse.queryForObject(
                        "SELECT refund_of::text FROM dw.fact_ticket_sales WHERE transaction_id = ?::uuid",
                        String.class,
                        refund))
                .isEqualTo(original);
    }

    @Test
    @Order(6)
    void k06NumericAndTimestampsKeepTheirPrecision() {
        String id = UUID.randomUUID().toString();
        List<ConsumerRecord<String, byte[]>> got = change(
                SALES,
                "transaction_id",
                id,
                () -> source.update(
                        "INSERT INTO ticket_transaction (transaction_id, sale_point_id, ticket_type, txn_type, amount, created_at)"
                                + " VALUES (?::uuid, 'APP-IOS', 'SINGLE', 'SALE', 12.34, now() - interval '1 second'"
                                + " + interval '0.123456 seconds')",
                        id));

        assertThat(etl(got)).isEmpty();
        Map<String, Object> sourceRow = source.queryForMap(
                "SELECT amount::text AS amount, created_at::text AS created FROM ticket_transaction "
                        + "WHERE transaction_id = ?::uuid",
                id);
        Map<String, Object> factRow = warehouse.queryForMap(
                "SELECT amount::text AS amount, created_at::text AS created FROM dw.fact_ticket_sales "
                        + "WHERE transaction_id = ?::uuid",
                id);
        assertThat(factRow).isEqualTo(sourceRow);
    }

    @Test
    @Order(7)
    void k07CustomerReferencesNeverReachTheWarehouse() {
        String id = UUID.randomUUID().toString();
        List<ConsumerRecord<String, byte[]>> got = change(
                SALES,
                "transaction_id",
                id,
                () -> source.update(
                        "INSERT INTO ticket_transaction (transaction_id, sale_point_id, ticket_type, txn_type, amount, customer_ref)"
                                + " VALUES (?::uuid, 'APP-IOS', 'MONTH', 'SALE', 900.00, 'cust-private-42')",
                        id));

        Map<String, DataException> rejected = etl(got);
        assertThat(rejected).containsKey(id);
        String raw = warehouse.queryForObject(
                "SELECT raw_payload FROM ops.dead_letter WHERE business_key LIKE ?", String.class, "%" + id);
        assertThat(raw).contains(id).doesNotContain("cust-private-42").doesNotContain("customer_ref");
        assertThat(warehouse.queryForObject("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'dw' AND column_name = 'customer_ref'
                        """, Long.class)).isZero();
    }

    @Test
    @Order(8)
    void k08ANewSourceColumnIsASchemaViolationUntilTheContractChanges() {
        owner.execute("ALTER TABLE ticket_transaction ADD COLUMN note TEXT NULL");
        String id = UUID.randomUUID().toString();
        List<ConsumerRecord<String, byte[]>> got = change(
                SALES,
                "transaction_id",
                id,
                () -> source.update(
                        "INSERT INTO ticket_transaction (transaction_id, sale_point_id, ticket_type, txn_type, amount, note)"
                                + " VALUES (?::uuid, 'APP-IOS', 'SINGLE', 'SALE', 2.50, 'hello')",
                        id));

        assertThat(count(got, SALES, j -> j.has("note"))).isEqualTo(1);
        Map<String, DataException> rejected = etl(got);
        assertThat(rejected).containsKey(id);
        assertThat(rejected.get(id).stage().name()).isEqualTo("SCHEMA");
        assertThat(rejected.get(id).ruleId()).isEqualTo("DQ-01");
        assertThat(new String(got.getFirst().value(), StandardCharsets.UTF_8)).contains("\"note\"");
    }
}
