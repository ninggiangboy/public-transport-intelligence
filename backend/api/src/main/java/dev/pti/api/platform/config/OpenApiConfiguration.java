package dev.pti.api.platform.config;

import dev.pti.api.platform.adapter.in.security.EndpointRules;
import dev.pti.api.platform.adapter.in.security.EndpointRules.Access;
import dev.pti.api.platform.domain.ProblemType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * The OpenAPI document (DOC-31 §12). The security requirement and the error responses of every operation come from the
 * endpoint matrix and the Problem catalog, not from annotations, so the document cannot disagree with the rules that
 * are enforced. Operation ids are the controller method names of DOC-32 ({@code getCurrentUser}); the build-time export
 * and the diff against {@code main} are P4-15.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String BEARER = "bearerAuth";
    static final String PROBLEM = "Problem";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    OpenAPI ptiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Public Transport Intelligence API")
                        .version("v1")
                        .description("Transit, insight, alert and ETL operations API of the PTI platform."))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("A Keycloak access token of realm pti.")));
    }

    @Bean
    OpenApiCustomizer endpointMatrix() {
        List<EndpointRules.Rule> rules = EndpointRules.api(true);
        return openApi -> {
            openApi.getComponents().addSchemas(PROBLEM, problemSchema());
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths()
                    .forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                        Access access = EndpointRules.accessFor(rules, HttpMethod.valueOf(method.name()), sample(path))
                                .orElse(Access.ANONYMOUS);
                        describe(operation, access);
                    }));
        };
    }

    private static void describe(Operation operation, Access access) {
        if (access != Access.ANONYMOUS) {
            operation.addSecurityItem(new SecurityRequirement().addList(BEARER));
            problem(operation, ProblemType.UNAUTHORIZED);
        }
        if (access == Access.OPERATOR) {
            problem(operation, ProblemType.FORBIDDEN);
        }
        if (operation.getParameters() != null || operation.getRequestBody() != null) {
            problem(operation, ProblemType.VALIDATION_ERROR);
        }
        problem(operation, ProblemType.RATE_LIMITED);
        problem(operation, ProblemType.SERVICE_UNAVAILABLE);
    }

    private static void problem(Operation operation, ProblemType type) {
        String status = String.valueOf(type.status());
        if (operation.getResponses().get(status) != null) {
            return;
        }
        Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + PROBLEM);
        operation
                .getResponses()
                .addApiResponse(
                        status,
                        new ApiResponse()
                                .description(type.title())
                                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(reference))));
    }

    private static Schema<?> problemSchema() {
        List<String> types =
                Arrays.stream(ProblemType.values()).map(ProblemType::urn).toList();
        ObjectSchema error = new ObjectSchema();
        error.addProperty("field", new StringSchema());
        error.addProperty("message", new StringSchema());
        ObjectSchema problem = new ObjectSchema();
        problem.addProperty("type", new StringSchema()._enum(types));
        problem.addProperty("title", new StringSchema());
        problem.addProperty("status", new IntegerSchema());
        problem.addProperty("detail", new StringSchema());
        problem.addProperty("instance", new StringSchema());
        problem.addProperty("traceId", new StringSchema());
        problem.addProperty("errors", new ArraySchema().items(error));
        problem.setRequired(List.of("type", "title", "status", "traceId"));
        problem.setAdditionalProperties(true);
        return problem;
    }

    private static String sample(String path) {
        return path.replaceAll("\\{[^/}]+}", "x");
    }
}
