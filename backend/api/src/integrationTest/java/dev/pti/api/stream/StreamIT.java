package dev.pti.api.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.api.ApiKafka;
import dev.pti.api.testing.JwtFixture;
import dev.pti.api.testing.StreamFixtures;
import dev.pti.common.json.MessageJson;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET /stream} end to end (DOC-26 §14, DOC-33 §8): a real broker, the real server, and a client that reads the
 * response line by line as a browser does. The application starts after two events are already on the topic, so the
 * prefill of DOC-26 §4.2 has something to load (RT-06). No database: the stream does not need one.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=-1",
            "management.health.db.enabled=false",
            "pti.observability.freshness-probe.enabled=false",
            "pti.api.rate-limit.enabled=true",
            "pti.api.rate-limit.public-per-minute=1000",
            "pti.api.rate-limit.sse-per-ip=5",
            "pti.api.sse.heartbeat-interval=1s"
        })
@ActiveProfiles("static-jwt")
class StreamIT {

    private static final String PREFIX = ApiKafka.newPrefix();
    private static final Path PUBLIC_KEY = tempFile("public-key", ".pem");
    private static final Path TOKEN_FILE = tempFile("webhook-token", ".txt");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Published before the application starts (RT-06). */
    private static final String BEFORE_START_1 =
            StreamFixtures.ulid(Instant.now().minusSeconds(120));

    private static final String BEFORE_START_2 =
            StreamFixtures.ulid(Instant.now().minusSeconds(110));

    private static KafkaProducer<String, String> producer;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MeterRegistry meters;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        ApiKafka.createTopic(PREFIX, ApiKafka.UI_TOPIC, 3);
        producer = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ApiKafka.bootstrapServers()),
                new StringSerializer(),
                new StringSerializer());
        // One key, so one partition, so the prefill reads them in this order.
        String topic = PREFIX + ApiKafka.UI_TOPIC;
        producer.send(new ProducerRecord<>(topic, "prefill", alert(BEFORE_START_1, "PUBLIC", "18", null)))
                .get();
        producer.send(new ProducerRecord<>(topic, "prefill", alert(BEFORE_START_2, "PUBLIC", "18", null)))
                .get();
        Files.writeString(TOKEN_FILE, "t0ken");
        JwtFixture.writePublicKey(PUBLIC_KEY);
        registry.add("spring.kafka.bootstrap-servers", ApiKafka::bootstrapServers);
        registry.add("pti.kafka.topic-prefix", () -> PREFIX);
        registry.add("pti.api.alert-webhook.token-file", TOKEN_FILE::toString);
        registry.add("pti.api.security.static-jwt.public-key-file", PUBLIC_KEY::toString);
    }

    @AfterAll
    static void closeProducer() {
        producer.close();
    }

    private static Path tempFile(String prefix, String suffix) {
        try {
            Path file = Files.createTempFile(prefix, suffix);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ events

    private static String alert(String id, String audience, @Nullable String routeId, @Nullable Instant source) {
        ObjectNode node = MessageJson.mapper().createObjectNode();
        node.put("id", id);
        node.put("type", "alert.created");
        node.put("channel", "alerts");
        node.put("audience", audience);
        node.put("occurred_at", Instant.now().toString());
        if (source == null) {
            node.putNull("source_record_ts");
        } else {
            node.put("source_record_ts", source.toString());
        }
        if (routeId == null) {
            node.putNull("route_id");
        } else {
            node.put("route_id", routeId);
        }
        ObjectNode data = node.putObject("data");
        data.put("id", UUID.randomUUID().toString());
        data.put("type", "DISRUPTION");
        data.put("audience", audience);
        if (routeId != null) {
            data.put("routeId", routeId);
        }
        data.put("title", "Delays");
        data.putObject("body").put("disruptionId", "d1").put("likelyCause", "detour");
        data.put("acknowledgedBy", "user:operator");
        return node.toString();
    }

    private static java.util.concurrent.Future<?> publish(String envelope) {
        String key = MessageJson.mapper().readTree(envelope).path("id").asString();
        return producer.send(new ProducerRecord<>(PREFIX + ApiKafka.UI_TOPIC, key, envelope));
    }

    private static String publishAlert(String audience) throws Exception {
        String id = StreamFixtures.ulid(Instant.now());
        publish(alert(id, audience, "18", Instant.now().minusSeconds(2))).get();
        return id;
    }

    // ------------------------------------------------------------------------------------------ client

    record SseFrame(
            @Nullable String id,
            @Nullable String event,
            @Nullable String data,
            @Nullable String retry) {
        JsonNode json() {
            return MessageJson.mapper().readTree(data);
        }
    }

    /** One open stream; frames are parsed on a virtual thread as the lines come. */
    final class Client implements AutoCloseable {
        final HttpResponse<Stream<String>> response;
        final BlockingQueue<SseFrame> frames = new LinkedBlockingQueue<>();

        Client(String query, @Nullable String lastEventId, @Nullable String token) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/stream" + query))
                    .header("Accept", "text/event-stream");
            if (lastEventId != null) {
                request.header("Last-Event-ID", lastEventId);
            }
            if (token != null) {
                request.header("Authorization", JwtFixture.bearer(token));
            }
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() == 200) {
                Thread.ofVirtual().start(this::read);
            }
        }

        private void read() {
            String id = null;
            String event = null;
            String retry = null;
            StringBuilder data = null;
            try {
                for (String line : (Iterable<String>) response.body()::iterator) {
                    if (line.isEmpty()) {
                        frames.add(new SseFrame(id, event, data == null ? null : data.toString(), retry));
                        id = null;
                        event = null;
                        retry = null;
                        data = null;
                    } else if (line.startsWith("id:")) {
                        id = line.substring(3).strip();
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).strip();
                    } else if (line.startsWith("retry:")) {
                        retry = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        data = (data == null ? new StringBuilder() : data.append('\n')).append(line.substring(5));
                    }
                }
            } catch (RuntimeException e) {
                // closed by the test
            }
        }

        /** The next frame of the given event type, skipping heartbeats and the others. */
        SseFrame next(String type) throws InterruptedException {
            assertThat(response.statusCode()).as("status of the stream").isEqualTo(200);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                SseFrame frame = frames.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(frame.event())) {
                    return frame;
                }
            }
            throw new AssertionError("No " + type + " frame within 20 s");
        }

        /** The ids of the alert frames received within {@code wait}, in order. */
        List<String> alertIds(Duration wait) throws InterruptedException {
            List<String> ids = new ArrayList<>();
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                SseFrame frame = frames.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null && "alert.created".equals(frame.event())) {
                    ids.add(frame.id());
                }
            }
            return ids;
        }

        @Override
        public void close() {
            response.body().close();
        }
    }

    // ------------------------------------------------------------------------------------------ tests

    @Test
    void rt01HundredAlertsArriveInOrderWithRetryFirst() throws Exception {
        try (Client client = new Client("?channels=alerts", null, null)) {
            SseFrame first = client.frames.poll(10, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(first.retry()).isEqualTo("1000");
            assertThat(client.response.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
            assertThat(client.response.headers().firstValue("X-Accel-Buffering"))
                    .contains("no");
            List<String> published = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String id = StreamFixtures.ulid(Instant.now());
                // One key, so one partition: the order on the topic is the order of publishing.
                producer.send(new ProducerRecord<>(PREFIX + ApiKafka.UI_TOPIC, "one", alert(id, "PUBLIC", "18", null)));
                published.add(id);
            }
            producer.flush();

            List<String> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                received.addAll(client.alertIds(Duration.ofMillis(200)));
                return received.size() >= 100;
            });
            assertThat(received).containsExactlyElementsOf(published);
        }
    }

    @Test
    void anonymousClientsGetThePublicProjectionAndNoOperationsEvents() throws Exception {
        try (Client client = new Client("?channels=alerts", null, null)) {
            client.frames.poll(10, TimeUnit.SECONDS);
            publishAlert("OPERATIONS");
            String publicId = publishAlert("PUBLIC");

            SseFrame frame = client.next("alert.created");

            assertThat(frame.id()).isEqualTo(publicId);
            JsonNode data = frame.json().path("data");
            assertThat(data.has("acknowledgedBy")).isFalse();
            assertThat(data.path("body").has("likelyCause")).isFalse();
            assertThat(data.path("link").asString()).startsWith("/map?route=18");
            assertThat(frame.json().has("audience")).isFalse();
        }
    }

    @Test
    void rt02AClientThatReconnectsWithLastEventIdGetsWhatItMissed() throws Exception {
        String seen;
        try (Client client = new Client("?channels=alerts", null, JwtFixture.viewer())) {
            client.frames.poll(10, TimeUnit.SECONDS);
            String id = publishAlert("OPERATIONS");
            seen = client.next("alert.created").id();
            assertThat(seen).isEqualTo(id);
        }
        String missed1 = publishAlert("OPERATIONS");
        String missed2 = publishAlert("PUBLIC");
        // The hub has them once the consumer polled; 100 ms fetch wait, so a second is plenty.
        await().pollDelay(Duration.ofSeconds(1)).until(() -> true);

        try (Client client = new Client("?channels=alerts", seen, JwtFixture.viewer())) {
            List<String> ids = new ArrayList<>();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                ids.addAll(client.alertIds(Duration.ofMillis(200)));
                return ids.containsAll(List.of(missed1, missed2));
            });
            assertThat(ids).startsWith(missed1, missed2).doesNotContain(seen);
        }
    }

    @Test
    void rt06APodThatStartsAfterAnEventReplaysItFromThePrefill() throws Exception {
        try (Client client = new Client("?channels=alerts", BEFORE_START_1, null)) {
            SseFrame frame = client.next("alert.created");

            assertThat(frame.id()).isEqualTo(BEFORE_START_2);
        }
    }

    @Test
    void rt03AnUnknownOrTooOldLastEventIdGetsResync() throws Exception {
        String tooOld = StreamFixtures.ulid(Instant.now().minus(Duration.ofMinutes(6)));
        try (Client client = new Client("?channels=alerts,vehicles", tooOld, null)) {
            SseFrame resync = client.next("resync");

            assertThat(resync.id()).isNull();
            assertThat(resync.json().at("/data/reason").asString()).isEqualTo("BUFFER_EXPIRED");
            assertThat(resync.json().at("/data/channels").toString()).isEqualTo("[\"alerts\"]");
        }
    }

    @Test
    void rt13HeartbeatsComeWithoutAnId() throws Exception {
        try (Client client = new Client("", null, null)) {
            SseFrame heartbeat = client.next("heartbeat");

            assertThat(heartbeat.id()).isNull();
            assertThat(heartbeat.json().at("/data/businessNow").asString()).isNotBlank();
        }
    }

    @Test
    void rt09AnonymousCallersCannotOpenJobsOrDlq() throws Exception {
        try (Client jobs = new Client("?channels=jobs", null, null);
                Client dlq = new Client("?channels=alerts,dlq", null, null);
                Client viewer = new Client("?channels=jobs", null, JwtFixture.viewer())) {
            assertThat(jobs.response.statusCode()).isEqualTo(401);
            assertThat(dlq.response.statusCode()).isEqualTo(401);
            assertThat(viewer.response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void badParametersAreProblemDetailsBeforeTheStreamOpens() throws Exception {
        try (Client unknown = new Client("?channels=weather", null, null);
                Client routes = new Client(
                        "?routeId="
                                + String.join(
                                        ",",
                                        java.util.stream.IntStream.rangeClosed(1, 21)
                                                .mapToObj(i -> "r" + i)
                                                .toList()),
                        null,
                        null)) {
            assertThat(unknown.response.statusCode()).isEqualTo(400);
            assertThat(unknown.response.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
            assertThat(String.join("", unknown.response.body().toList())).contains("validation-error");
            assertThat(routes.response.statusCode()).isEqualTo(400);
        }
    }

    @Test
    void rt10TheSixthStreamOfAnAnonymousIpIsRefused() throws Exception {
        List<Client> open = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                open.add(new Client("", null, null));
            }
            try (Client sixth = new Client("", null, null)) {
                assertThat(sixth.response.statusCode()).isEqualTo(429);
                assertThat(sixth.response.headers().firstValue("Retry-After")).isPresent();
            }
        } finally {
            open.forEach(Client::close);
        }
    }

    @Test
    void theEndToEndLatencyIsMeasuredForEventsWithASourceRecordTime() throws Exception {
        try (Client client = new Client("?channels=alerts", null, null)) {
            client.frames.poll(10, TimeUnit.SECONDS);
            publishAlert("PUBLIC");
            client.next("alert.created");
        }

        assertThat(meters.find("pti.end.to.end.latency")
                        .tag("channel", "alerts")
                        .timer())
                .isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isPositive());
        assertThat(meters.find("pti.api.publish.to.emit")
                        .tag("channel", "alerts")
                        .timer())
                .isNotNull();
    }

    private double sseConnections() {
        return meters.find("pti.api.sse.connections").gauges().stream()
                .mapToDouble(g -> g.value())
                .max()
                .orElse(0);
    }

    /** A closed client is noticed at the next write (a heartbeat a second later); the caps count until then. */
    @BeforeEach
    void previousStreamsAreGone() {
        await().atMost(Duration.ofSeconds(10)).until(() -> sseConnections() == 0);
    }
}
