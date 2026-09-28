package dev.pti.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.spike.kafka.ProbeListener;
import io.awspring.cloud.s3.S3Template;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.tracing.Tracer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

/** S-06 points 0(f), 1, 2, 3 (DR-53): the rest of the stack on Boot 4.1 / Java 25. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spike.kafka.enabled=true",
            "resilience4j.circuitbreaker.instances.warehouse.sliding-window-size=10"
        })
@Import({TestPostgres.class, InfraContainers.class})
class LibraryCompatibilityTest {

    @LocalServerPort
    int port;

    @Autowired
    LockProvider lockProvider;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    S3Template s3Template;

    @Autowired
    S3Client s3Client;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ProbeListener probe;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @Autowired
    Tracer tracer;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    DataSource dataSource;

    RestClient http() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void shedLockUsesDatabaseTime() {
        var executor = new DefaultLockingTaskExecutor(lockProvider);
        var ran = new boolean[1];
        executor.executeWithLock(
                (Runnable) () -> ran[0] = true,
                new LockConfiguration(Instant.now(), "spike-job", Duration.ofSeconds(30), Duration.ZERO));
        assertThat(ran[0]).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shedlock WHERE name = 'spike-job'", Integer.class))
                .isOne();
    }

    @Test
    void s3PathStyleAgainstSeaweedfs() {
        s3Client.createBucket(b -> b.bucket("raw"));
        s3Template.upload(
                "raw",
                "gtfs.vehicle_positions/dt=2026-09-28/hour=07/x.json.gz",
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        var listed = s3Template.listObjects("raw", "gtfs.vehicle_positions/");
        System.out.println("[S-06] s3 objects: " + listed.size());
        assertThat(listed).hasSize(1);
    }

    @Test
    void kafkaBatchListenerPausesAndRedelivers() {
        probe.failNext.set(true);
        kafka.send("probe", "18", "hello");
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(probe.processed).contains("hello"));
        System.out.println("[S-06] kafka processed after transient error: " + probe.processed);
    }

    @Test
    void webActuatorSpringdocResilience4jTracing() {
        String ping = http().get().uri("/api/v1/ping").retrieve().body(String.class);
        String apiDocs = http().get().uri("/v3/api-docs").retrieve().body(String.class);
        circuitBreakers.circuitBreaker("warehouse").executeSupplier(() -> 1);
        String prometheus = http().get().uri("/actuator/prometheus").retrieve().body(String.class);
        System.out.println("[S-06] ping: " + ping);
        System.out.println("[S-06] openapi version: "
                + jsonMapper.readTree(apiDocs).path("openapi").asString());
        System.out.println("[S-06] tracer: " + tracer.getClass().getName());
        System.out.println("[S-06] datasource: " + dataSource.getClass().getName());
        assertThat(ping).contains("\"status\":\"ok\"").contains("\"virtualThread\":true");
        assertThat(apiDocs).contains("/api/v1/ping");
        assertThat(prometheus).contains("resilience4j_circuitbreaker_state").contains("jvm_threads_live_threads");
        assertThat(tracer.getClass().getName()).doesNotContain("Noop");
    }
}
