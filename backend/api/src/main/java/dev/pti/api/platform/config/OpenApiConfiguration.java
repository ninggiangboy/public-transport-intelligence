package dev.pti.api.platform.config;

import dev.pti.api.platform.adapter.in.security.EndpointRules;
import dev.pti.api.platform.adapter.in.security.EndpointRules.Access;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.ProblemType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
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
import org.springdoc.core.customizers.GlobalOperationComponentsCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.JsonNode;

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

    static {
        // CallerArgumentResolver fills Caller from the security context; without this springdoc documents it as a
        // required query parameter, and the generated frontend client would have to send it.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Caller.class);
        // Free-form JSON objects (a state snapshot, a summary); as a bean JsonNode would turn into isArray, isBoolean,
        // ...
        SpringDocUtils.getConfig()
                .replaceWithSchema(
                        JsonNode.class,
                        new ObjectSchema().additionalProperties(true).description("Free-form JSON object"));
    }

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

    /**
     * springdoc only derives a response schema from the return type when the {@code @ApiResponse} has no content, and
     * the success responses carry their DOC-32 example as content. Without this the generated frontend client would
     * type every response body as {@code unknown}.
     */
    @Bean
    ModelConverter requiredRecordComponents() {
        return new RequiredRecordComponents();
    }

    @Bean
    GlobalOperationComponentsCustomizer successSchemas() {
        return new SuccessSchemas();
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
        // Every handler answers an undeclared query parameter with 400 (UnknownQueryParameterInterceptor), so the
        // response applies to operations without parameters too.
        problem(operation, ProblemType.VALIDATION_ERROR);
        problem(operation, ProblemType.RATE_LIMITED);
        problem(operation, ProblemType.SERVICE_UNAVAILABLE);
        // Every error is Problem Details (DOC-30 §3). springdoc gives an annotated error response without content the
        // schema of the method's return type instead.
        operation.getResponses().forEach((status, response) -> {
            if (status.charAt(0) == '4' || status.charAt(0) == '5') {
                response.setContent(problemContent());
            }
        });
    }

    private static void problem(Operation operation, ProblemType type) {
        String status = String.valueOf(type.status());
        if (operation.getResponses().get(status) != null) {
            return;
        }
        operation
                .getResponses()
                .addApiResponse(
                        status, new ApiResponse().description(type.title()).content(problemContent()));
    }

    private static Content problemContent() {
        Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + PROBLEM);
        return new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(reference));
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

    /**
     * springdoc only derives a response schema from the return type when the {@code @ApiResponse} has no content, and
     * the success responses carry their DOC-32 example as content. Without this the generated frontend client would
     * type every response body as {@code unknown}.
     */
    static final class SuccessSchemas implements GlobalOperationComponentsCustomizer {

        @Override
        public Operation customize(Operation operation, HandlerMethod handlerMethod) {
            return operation;
        }

        @Override
        public Operation customize(Operation operation, Components components, HandlerMethod handlerMethod) {
            ResolvableType body = ResolvableType.forMethodReturnType(handlerMethod.getMethod());
            if (HttpEntity.class.isAssignableFrom(body.toClass())) {
                body = body.getGeneric(0);
            }
            Class<?> type = body.toClass();
            if (type == Void.class || type == void.class || type == Object.class || operation.getResponses() == null) {
                return operation;
            }
            Schema<?> schema = null;
            for (var response : operation.getResponses().entrySet()) {
                Content content = response.getValue().getContent();
                if (!response.getKey().startsWith("2") || content == null) {
                    continue;
                }
                for (var media : content.entrySet()) {
                    if (media.getValue().getSchema() == null && media.getKey().contains("json")) {
                        if (schema == null) {
                            schema = SpringDocAnnotationsUtils.extractSchema(
                                    components, body.getType(), null, null, SpecVersion.V31);
                        }
                        media.getValue().setSchema(schema);
                    }
                }
            }
            return operation;
        }
    }
}
