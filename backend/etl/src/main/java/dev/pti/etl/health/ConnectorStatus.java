package dev.pti.etl.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * State of the {@code debezium-ticketing} connector from the Kafka Connect REST API, cached for 15 seconds (DOC-20
 * §6.1), and the gauge {@code pti_connect_connector_running} (DR-71).
 */
public class ConnectorStatus {

    public static final String CONNECTOR = "debezium-ticketing";
    private static final Duration CACHE = Duration.ofSeconds(15);

    private final RestClient client;
    private final Clock clock;
    private @Nullable Instant checkedAt;
    private String state = "UNKNOWN";

    public ConnectorStatus(RestClient.Builder builder, URI connectUrl, Clock clock) {
        this.client = builder.baseUrl(connectUrl.toString()).build();
        this.clock = clock;
    }

    public void bindTo(MeterRegistry meters) {
        Gauge.builder("pti.connect.connector.running", () -> isRunning() ? 1 : 0)
                .tag("connector", CONNECTOR)
                .register(meters);
    }

    /** RUNNING when the connector and all of its tasks run; the error text otherwise. */
    public synchronized String state() {
        Instant now = clock.instant();
        if (checkedAt == null || Duration.between(checkedAt, now).compareTo(CACHE) >= 0) {
            state = fetch();
            checkedAt = now;
        }
        return state;
    }

    public boolean isRunning() {
        return "RUNNING".equals(state());
    }

    private String fetch() {
        try {
            JsonNode status = client.get()
                    .uri("/connectors/{name}/status", CONNECTOR)
                    .retrieve()
                    .body(JsonNode.class);
            if (status == null) {
                return "UNKNOWN";
            }
            String connector = status.path("connector").path("state").asString("UNKNOWN");
            if (!connector.equals("RUNNING")) {
                return connector;
            }
            for (JsonNode task : status.path("tasks")) {
                String taskState = task.path("state").asString("UNKNOWN");
                if (!taskState.equals("RUNNING")) {
                    return "TASK_" + taskState;
                }
            }
            return status.path("tasks").isEmpty() ? "NO_TASKS" : "RUNNING";
        } catch (RuntimeException e) {
            return "UNREACHABLE";
        }
    }
}
