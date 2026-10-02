package dev.pti.api.platform.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Marks every record component without jspecify {@code @Nullable} as required in the OpenAPI document. Jackson writes
 * with {@code default-property-inclusion: non_null}, so such a component is always present in a response and a
 * {@code @Nullable} one is absent when it has no value; the generated frontend types (DOC-34 §9.4) then tell the two
 * apart instead of making every property optional. springdoc registers every {@link ModelConverter} bean.
 */
final class RequiredRecordComponents implements ModelConverter {

    private static final String COMPONENTS = "#/components/schemas/";

    @Override
    public @Nullable Schema<?> resolve(
            AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> schema = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (schema == null || type.getType() == null) {
            return schema;
        }
        Class<?> raw = Json.mapper().constructType(type.getType()).getRawClass();
        if (!raw.isRecord()) {
            return schema;
        }
        Schema<?> model = schema;
        String ref = schema.get$ref();
        if (ref != null && ref.startsWith(COMPONENTS)) {
            model = context.getDefinedModels().get(ref.substring(COMPONENTS.length()));
        }
        if (model == null || model.getProperties() == null) {
            return schema;
        }
        Set<String> required = new LinkedHashSet<>(model.getRequired() == null ? List.of() : model.getRequired());
        for (RecordComponent component : raw.getRecordComponents()) {
            boolean nullable = component.getAnnotatedType().isAnnotationPresent(Nullable.class)
                    || component.isAnnotationPresent(Nullable.class);
            if (!nullable && model.getProperties().containsKey(component.getName())) {
                required.add(component.getName());
            }
        }
        if (!required.isEmpty()) {
            model.setRequired(List.copyOf(required));
        }
        return schema;
    }
}
