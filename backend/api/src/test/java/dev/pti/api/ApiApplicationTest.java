package dev.pti.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.testing.JwtFixture;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application as it starts outside the tests of one slice (DOC-49 §8: one context-start test per app): real
 * Tomcat, the default profile with the Keycloak decoder (which does not contact Keycloak until a token arrives), the
 * management port for the probes and Prometheus, and the client IP as Tomcat's remote IP valve reports it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "management.health.db.enabled=false",
            "pti.observability.freshness-probe.enabled=false",
            "pti.api.rate-limit.public-per-minute=3"
        })
class ApiApplicationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Value("${local.management.port}")
    private int managementPort;

    @Value("${server.tomcat.remoteip.internal-proxies}")
    private String internalProxies;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void webhookToken(DynamicPropertyRegistry registry) throws IOException {
        Path token = Files.createTempFile("webhook-token", ".txt");
        token.toFile().deleteOnExit();
        Files.writeString(token, "a-token");
        registry.add("pti.api.alert-webhook.token-file", token::toString);
    }

    private HttpResponse<String> get(int onPort, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + onPort + path));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("The context starts without a database and without Keycloak")
    void contextStarts() {
        assertThat(context.getBean("reader")).isNotNull();
        assertThat(context.getBean("operator")).isNotNull();
        assertThat(context.getBean("readerTx")).isNotNull();
        assertThat(context.getBean("operatorTx")).isNotNull();
    }

    @Test
    @DisplayName("Probes and Prometheus are on the management port and need no token")
    void managementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode())
                .isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode())
                .isEqualTo(200);
        HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_memory_used_bytes", "application=\"api\"");
    }

    @Test
    @DisplayName("Actuator is not served on the application port")
    void actuatorIsNotOnTheApplicationPort() throws Exception {
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Errors leave as Problem Details with the trace id, also from the security filters")
    void problemsOnTheRealServer() throws Exception {
        HttpResponse<String> response = get(port, "/api/v1/insights/otp");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("X-Trace-Id"))
                .hasValueSatisfying(id -> assertThat(id).matches("[0-9a-f]{32}"));
        assertThat(response.body()).contains("urn:pti:problem:unauthorized");
    }

    @Test
    @DisplayName("Without profile dev the OpenAPI document is not served")
    void openApiIsOffOutsideDev() throws Exception {
        assertThat(get(port, "/v3/api-docs").statusCode()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("A token that cannot be checked because Keycloak is unreachable is a 503 to retry, not a 500")
    void keycloakUnreachableIsServiceUnavailable() throws Exception {
        HttpResponse<String> response =
                get(port, "/api/v1/me", "Authorization", JwtFixture.bearer(JwtFixture.viewer()));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        assertThat(response.body())
                .contains("urn:pti:problem:service-unavailable")
                .doesNotContain("keycloak");
    }

    @Test
    @DisplayName("AG-12 the client is the address that a trusted proxy reports in X-Forwarded-For")
    void rateLimitFollowsForwardedFor() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(get(port, "/api/v1/me", "X-Forwarded-For", "198.51.100.7")
                            .statusCode())
                    .isEqualTo(200);
        }

        HttpResponse<String> denied = get(port, "/api/v1/me", "X-Forwarded-For", "198.51.100.7");

        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.headers().firstValue("Retry-After")).isPresent();
        assertThat(get(port, "/api/v1/me", "X-Forwarded-For", "198.51.100.8").statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("AG-13 only private ranges and loopback are trusted proxies, so a client cannot choose its IP")
    void onlyPrivateProxiesAreTrusted() {
        Pattern trusted = Pattern.compile(internalProxies);

        for (String proxy : new String[] {"10.1.2.3", "172.16.0.9", "172.31.255.1", "192.168.1.20", "127.0.0.1"}) {
            assertThat(trusted.matcher(proxy).matches()).as(proxy).isTrue();
        }
        for (String client : new String[] {"203.0.113.9", "8.8.8.8", "172.32.0.1", "11.0.0.1", "192.169.0.1"}) {
            assertThat(trusted.matcher(client).matches()).as(client).isFalse();
        }
    }
}
