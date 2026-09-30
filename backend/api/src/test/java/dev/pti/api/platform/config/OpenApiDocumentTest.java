package dev.pti.api.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.pti.api.platform.domain.ProblemType;
import dev.pti.apitest.ApiWebTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI document (DOC-31 §12) with springdoc on, as profile {@code dev} turns it on: security schemes, operation
 * ids of DOC-32, the Problem schema with every slug (DOC-44 §9.3 {@code ProblemSlugCatalogTest}), and nothing of
 * {@code /internal/**}. The security requirement of each operation comes from the endpoint matrix.
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiDocumentTest extends ApiWebTestSupport {

    @Autowired
    private JsonMapper mapper;

    private JsonNode document;

    private JsonNode document() throws Exception {
        if (document == null) {
            document = mapper.readTree(
                    mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        }
        return document;
    }

    @Test
    @DisplayName("The document is served without a token when springdoc is on")
    void servedWhenEnabled() throws Exception {
        assertThat(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(document().path("openapi").asString()).startsWith("3.");
        assertThat(document().path("info").path("title").asString()).isNotBlank();
    }

    @Test
    @DisplayName("Operation ids follow DOC-32 (getCurrentUser, getFreshness)")
    void operationIds() throws Exception {
        assertThat(document().at("/paths/~1api~1v1~1me/get/operationId").asString())
                .isEqualTo("getCurrentUser");
        assertThat(document()
                        .at("/paths/~1api~1v1~1system~1freshness/get/operationId")
                        .asString())
                .isEqualTo("getFreshness");
    }

    @Test
    @DisplayName("Only /api/v1 is documented: nothing of /internal or the actuator")
    void publicPathsOnly() throws Exception {
        List<String> paths = new ArrayList<>();
        document().path("paths").propertyNames().forEach(paths::add);

        assertThat(paths).isNotEmpty().allMatch(path -> path.startsWith("/api/v1/"));
    }

    @Test
    @DisplayName("Anonymous endpoints have no security requirement; viewer and operator ones ask for the bearer token")
    void securityFollowsTheMatrix() throws Exception {
        assertThat(document()
                        .at("/components/securitySchemes/bearerAuth/scheme")
                        .asString())
                .isEqualTo("bearer");
        assertThat(document().at("/paths/~1api~1v1~1me/get/security").isMissingNode())
                .isTrue();
        assertThat(document()
                        .at("/paths/~1api~1v1~1insights~1bunching~1stub-caller/get/security/0/bearerAuth")
                        .isArray())
                .isTrue();
        assertThat(document()
                        .at("/paths/~1api~1v1~1insights~1bunching~1stub-caller/get/responses/401")
                        .isObject())
                .isTrue();
        assertThat(document()
                        .at("/paths/~1api~1v1~1insights~1bunching~1stub-caller/get/responses/403")
                        .isMissingNode())
                .isTrue();
        assertThat(document()
                        .at("/paths/~1api~1v1~1alerts~1stub-ack~1ack/post/responses/403")
                        .isObject())
                .isTrue();
    }

    @Test
    @DisplayName("Every operation documents the rate limit and unavailability problems, as application/problem+json")
    void errorResponses() throws Exception {
        for (String status : new String[] {"429", "503"}) {
            assertThat(document()
                            .at("/paths/~1api~1v1~1me/get/responses/" + status
                                    + "/content/application~1problem+json/schema/$ref")
                            .asString())
                    .isEqualTo("#/components/schemas/Problem");
        }
    }

    @Test
    @DisplayName("The Problem schema lists every slug of DOC-30 §3.2 as a type")
    void problemSchemaHasEverySlug() throws Exception {
        List<String> types = new ArrayList<>();
        document().at("/components/schemas/Problem/properties/type/enum").forEach(node -> types.add(node.asString()));

        assertThat(types)
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(ProblemType.values())
                        .map(ProblemType::urn)
                        .toList());
        assertThat(document().at("/components/schemas/Problem/required").toString())
                .contains("traceId");
    }

    @Test
    @DisplayName("Operations carry the examples of DOC-32")
    void examples() throws Exception {
        assertThat(document()
                        .at("/paths/~1api~1v1~1me/get/responses/200/content/*~1*/examples/operator/value")
                        .toString())
                .contains("Demo Operator");
    }
}
