package dev.pti.apitest;

import dev.pti.api.testing.JwtFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The application as the security tests see it: the whole context over MockMvc, in the {@code static-jwt} profile so
 * that tokens signed by {@link JwtFixture} are the ones the API accepts, with the probe scheduler off and the stand-in
 * controllers of {@link StubEndpoints} imported. The database is never reached: the pools connect on first use, and
 * the ports of the transit, insight and alert features are the in-memory ones of {@link TransitFakes} and {@link
 * InsightFakes}.
 *
 * <p>Rate limiting is off here; {@code RateLimitWebTest} turns it on.
 */
@SpringBootTest(
        properties = {
            "pti.observability.freshness-probe.enabled=false",
            "pti.api.rate-limit.enabled=false",
            "management.server.port=-1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("static-jwt")
@Import({StubEndpoints.StubController.class, TransitFakes.class, InsightFakes.class})
public abstract class ApiWebTestSupport {

    /** Content of the webhook token file. */
    public static final String WEBHOOK_TOKEN = "t0ken-of-the-alertmanager-webhook";

    private static final Path PUBLIC_KEY = tempFile("public-key", ".pem");
    private static final Path TOKEN_FILE = tempFile("webhook-token", ".txt");

    @Autowired
    protected MockMvc mvc;

    @DynamicPropertySource
    static void security(DynamicPropertyRegistry registry) throws IOException {
        JwtFixture.writePublicKey(PUBLIC_KEY);
        Files.writeString(TOKEN_FILE, WEBHOOK_TOKEN + "\n");
        registry.add("pti.api.security.static-jwt.public-key-file", () -> PUBLIC_KEY.toString());
        registry.add("pti.api.alert-webhook.token-file", () -> TOKEN_FILE.toString());
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
}
