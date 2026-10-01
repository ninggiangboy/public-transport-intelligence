package dev.pti.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.pti.api.ApiKafka;
import dev.pti.api.insight.InsightIntegrationSupport;
import dev.pti.api.testing.JwtFixture;
import dev.pti.common.json.MessageJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The writes of the API and the broker, the real publisher in the real application: an acknowledgement and the
 * notices of Alertmanager end up on {@code pti.events.ui} as {@code alert.updated} and {@code alert.created} for the
 * audience of the row (DOC-32 E-21, E-80, DOC-33 §3, §5.5).
 */
class UiEventsEndToEndIT extends InsightIntegrationSupport {

    private static final String PREFIX = ApiKafka.newPrefix();
    private static final String WEBHOOK_TOKEN = "t0ken-of-the-alertmanager-webhook";
    private static final Path TOKEN_FILE = tokenFile();
    private static final String ALERT = "00000000-0000-0000-0000-0000000000a2";

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        ApiKafka.createTopic(PREFIX, ApiKafka.UI_TOPIC, 3);
        registry.add("spring.kafka.bootstrap-servers", ApiKafka::bootstrapServers);
        registry.add("pti.kafka.topic-prefix", () -> PREFIX);
        registry.add("pti.api.alert-webhook.token-file", TOKEN_FILE::toString);
    }

    private static Path tokenFile() {
        try {
            Path file = Files.createTempFile("webhook-token", ".txt");
            file.toFile().deleteOnExit();
            Files.writeString(file, WEBHOOK_TOKEN + "\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("EP-16 an acknowledgement is announced once on the topic, for the audience of the alert")
    void acknowledgement() throws Exception {
        insertAlert(ALERT, "BUNCHING", "OPERATIONS", "18", "2026-09-29T21:00:00Z", "bunching:a2");
        try (ApiKafka.Reader topic = new ApiKafka.Reader(PREFIX, ApiKafka.UI_TOPIC)) {
            for (int i = 0; i < 2; i++) {
                assertThat(mvc.perform(post("/api/v1/alerts/" + ALERT + "/ack")
                                        .header("Authorization", JwtFixture.bearer(JwtFixture.operator())))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                        .isEqualTo(200);
            }

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                List<ConsumerRecord<String, String>> records = topic.polled(r -> ALERT.equals(r.key()));
                assertThat(records).hasSize(1);
                JsonNode envelope =
                        MessageJson.mapper().readTree(records.getFirst().value());
                assertThat(envelope.path("type").asString()).isEqualTo("alert.updated");
                assertThat(envelope.path("channel").asString()).isEqualTo("alerts");
                assertThat(envelope.path("audience").asString()).isEqualTo("OPERATIONS");
                assertThat(envelope.path("route_id").asString()).isEqualTo("18");
                JsonNode data = envelope.path("data");
                assertThat(data.path("id").asString()).isEqualTo(ALERT);
                assertThat(data.path("acknowledgedBy").asString()).isEqualTo("user:operator");
                assertThat(data.path("link").asString()).isEqualTo("/map?route=18");
            });
        }
    }

    @Test
    @DisplayName(
            "EP-35 a firing and a resolved notice are announced as alert.created and alert.updated for engineering")
    void webhook() throws Exception {
        try (ApiKafka.Reader topic = new ApiKafka.Reader(PREFIX, ApiKafka.UI_TOPIC)) {
            for (String status : List.of("firing", "resolved")) {
                assertThat(mvc.perform(post("/internal/alerts/alertmanager")
                                        .header("Authorization", "Bearer " + WEBHOOK_TOKEN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"alerts": [{"status": "%s", "fingerprint": "e2e1",
                                                  "startsAt": "2026-09-29T21:00:00.000Z",
                                                  "labels": {"alertname": "GtfsRtFeedStale", "severity": "critical"},
                                                  "annotations": {"summary": "Stale"}}]}""".formatted(status)))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                        .isEqualTo(204);
            }
            String id = ownerText("SELECT id FROM ops.alert_event WHERE dedup_key LIKE 'am:e2e1:%'");

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                List<ConsumerRecord<String, String>> records = topic.polled(r -> id.equals(r.key()));
                assertThat(records).hasSize(2);
                assertThat(records)
                        .extracting(r -> MessageJson.mapper()
                                .readTree(r.value())
                                .path("type")
                                .asString())
                        .containsExactly("alert.created", "alert.updated");
                JsonNode created = MessageJson.mapper().readTree(records.get(0).value());
                assertThat(created.path("audience").asString()).isEqualTo("ENGINEERING");
                assertThat(created.path("route_id").isNull()).isTrue();
                assertThat(created.path("data").path("type").asString()).isEqualTo("FEED_STALE");
                assertThat(created.path("data").path("link").asString()).isEqualTo("/ops/jobs?kind=STREAM");
                JsonNode updated = MessageJson.mapper().readTree(records.get(1).value());
                assertThat(updated.path("data").has("resolvedAt")).isTrue();
            });
        }
    }
}
