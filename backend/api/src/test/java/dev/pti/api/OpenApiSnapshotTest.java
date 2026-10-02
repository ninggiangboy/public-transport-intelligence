package dev.pti.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The committed {@code backend/api/openapi.json} is the document the application serves (DOC-31 §12, AG-20, DOC-44
 * §9.3): the frontend generates its client from the file and CI diffs it against the base branch, so a change to an
 * endpoint that is not in the file fails the build. {@code ./gradlew :api:updateOpenApi} writes the file again.
 *
 * <p>The document comes from the whole application with springdoc on, as profile {@code dev} serves it; keys are
 * sorted so that the file only changes when the API does.
 */
@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=true",
            "management.server.port=-1",
            "management.health.db.enabled=false",
            "pti.observability.freshness-probe.enabled=false"
        })
@AutoConfigureMockMvc
class OpenApiSnapshotTest {

    /** {@code true} writes the file instead of comparing (the {@code updateOpenApi} task). */
    static final String UPDATE = "pti.openapi.update";

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void webhookToken(DynamicPropertyRegistry registry) throws IOException {
        Path token = Files.createTempFile("webhook-token", ".txt");
        token.toFile().deleteOnExit();
        Files.writeString(token, "a-token");
        registry.add("pti.api.alert-webhook.token-file", token::toString);
    }

    private static Path committed() {
        String root = System.getProperty("pti.repo-root");
        assertThat(root).as("the build sets pti.repo-root").isNotBlank();
        return Path.of(root, "backend", "api", "openapi.json");
    }

    /** The served document with every object's keys in order, two-space indented, ending with a newline. */
    private String served() throws Exception {
        String body =
                mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.writeValueAsString(sorted(JSON.readTree(body))) + "\n";
    }

    static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            names.sort(null);
            ObjectNode copy = JSON.createObjectNode();
            names.forEach(name -> copy.set(name, sorted(node.get(name))));
            return copy;
        }
        if (node.isArray()) {
            var copy = JSON.createArrayNode();
            node.forEach(element -> copy.add(sorted(element)));
            return copy;
        }
        return node;
    }

    @Test
    @DisplayName("AG-20: openapi.json is the document the API serves")
    void theCommittedDocumentIsUpToDate() throws Exception {
        String served = served();
        Path file = committed();
        if (Boolean.getBoolean(UPDATE)) {
            Files.writeString(file, served);
            return;
        }
        assertThat(Files.exists(file))
                .as("%s is missing; run ./gradlew :api:updateOpenApi", file)
                .isTrue();
        assertThat(served)
                .as("openapi.json differs from the API; run ./gradlew :api:updateOpenApi and commit the file")
                .isEqualTo(Files.readString(file));
    }
}
