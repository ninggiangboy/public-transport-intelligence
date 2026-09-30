package dev.pti.simulator.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.db.MigratedDatabases;
import dev.pti.simulator.SourceSimulatorApplication;
import dev.pti.simulator.feed.Feeds;
import dev.pti.testing.MetricCatalog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole simulator against the migrated pg-source, with Kafka out of reach: DOC-28 O-01 and O-02 (every metric of
 * the catalog, only its labels) and O-07 (the replication slot gauge, read as {@code source_simulator}).
 */
@SpringBootTest(classes = SourceSimulatorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// The running app sells tickets at real time into the shared ticketing_source; close it so it cannot write into the
// business-time window of the ticketing ITs that run after this class.
@DirtiesContext
class SimulatorMetricsIT {

    private static final MetricCatalog CATALOG = MetricCatalog.load("metric-catalog.txt");

    @LocalManagementPort
    int managementPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pti.datasource.ticketing.url", () -> MigratedDatabases.jdbcUrl("ticketing_source"));
        registry.add("pti.datasource.sim.url", () -> MigratedDatabases.jdbcUrl("pti_sim"));
        registry.add("pti.datasource.ticketing.password", () -> MigratedDatabases.password("source_simulator"));
        registry.add("pti.datasource.sim.password", () -> MigratedDatabases.password("source_simulator"));
        registry.add("pti.sim.feed.location", () -> "file:" + Feeds.realPath());
        // Nothing listens there: every send fails fast, which is what pti_errors_total needs to show up.
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
        registry.add("pti.observability.slot-probe.interval", () -> "1s");
        registry.add("server.port", () -> "0");
        registry.add("management.server.port", () -> "0");
    }

    @Test
    void o01ExposesEveryCatalogMetricWithOnlyItsLabels() throws Exception {
        String scrape = scrape();
        assertThat(CATALOG.missing(scrape, false)).isEmpty();
        assertThat(CATALOG.violations(scrape)).isEmpty();
    }

    @Test
    void o07ReportsTheWalKeptByAReplicationSlot() throws Exception {
        try (Connection c = MigratedDatabases.connect("ticketing_source", "debezium");
                Statement s = c.createStatement()) {
            s.execute("SELECT pg_create_logical_replication_slot('pti_it_slot', 'pgoutput')");
            try {
                try (Connection owner = MigratedDatabases.connect("ticketing_source", "ticketing_owner");
                        Statement w = owner.createStatement()) {
                    w.execute("CREATE TEMP TABLE wal_filler AS SELECT g, repeat('x', 200) AS pad"
                            + " FROM generate_series(1, 20000) g");
                    w.execute("UPDATE public.debezium_heartbeat SET ts = now()");
                }
                await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(() -> assertThat(scrape())
                                .containsPattern(
                                        "pti_source_replication_slot_retained_bytes\\{[^}]*slot=\"pti_it_slot\"[^}]*} "
                                                + "[1-9]"));
            } finally {
                s.execute("SELECT pg_drop_replication_slot('pti_it_slot')");
            }
        }
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
