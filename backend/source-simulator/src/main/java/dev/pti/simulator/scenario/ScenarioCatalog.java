package dev.pti.simulator.scenario;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalog of {@code GET /sim/scenarios} (DOC-25 §8), generated from each scenario's parameter record: the
 * {@link ScenarioParam} annotations, the Bean Validation constraints and the defaults. The UI builds its forms from
 * it; {@code POST} still validates for real.
 */
public final class ScenarioCatalog {

    private final JsonMapper mapper;
    private final Duration maxDuration;
    private final Supplier<List<String>> salePointSuggestions;

    /**
     * @param maxDuration {@code pti.sim.scenario.max-duration}, the upper bound of every {@code duration}
     * @param salePointSuggestions up to 20 active sale points, busiest first
     */
    public ScenarioCatalog(JsonMapper mapper, Duration maxDuration, Supplier<List<String>> salePointSuggestions) {
        this.mapper = mapper;
        this.maxDuration = maxDuration;
        this.salePointSuggestions = salePointSuggestions;
    }

    public Catalog describe(List<Scenario<?>> scenarios) {
        return new Catalog(scenarios.stream().map(this::describe).toList());
    }

    public Item describe(Scenario<?> scenario) {
        Record defaults = scenario.defaults();
        List<Param> params = new ArrayList<>();
        for (RecordComponent component : scenario.paramsType().getRecordComponents()) {
            params.add(param(component, defaults));
        }
        return new Item(scenario.name(), scenario.title(), scenario.description(), scenario.concurrency(), params);
    }

    private Param param(RecordComponent component, Record defaults) {
        ScenarioParam meta = component.getAnnotation(ScenarioParam.class);
        if (meta == null) {
            throw new IllegalStateException(component + " has no @ScenarioParam");
        }
        Object value;
        try {
            value = component.getAccessor().invoke(defaults);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ParamType type = meta.type() == ParamType.AUTO ? infer(component.getGenericType()) : meta.type();
        List<String> options = options(meta, component.getGenericType());
        Bounds bounds =
                switch (type) {
                    case INT, DOUBLE -> numberBounds(constraints(component));
                    case DOUBLE_LIST -> numberBounds(elementAnnotations(component.getAnnotatedType()));
                    case DURATION -> durationBounds(component);
                    default -> Bounds.NONE;
                };
        return new Param(
                component.getName(),
                meta.label(),
                type,
                value == null && !meta.nullable(),
                meta.nullable(),
                value == null ? null : mapper.valueToTree(value),
                bounds.min(),
                bounds.max(),
                options.isEmpty() ? null : options,
                meta.salePointSuggestions() ? salePointSuggestions.get() : null);
    }

    private static ParamType infer(Type type) {
        if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
            Type element = list.getActualTypeArguments()[0];
            if (element instanceof Class<?> c && c.isEnum()) {
                return ParamType.ENUM_LIST;
            }
            if (element == Double.class) {
                return ParamType.DOUBLE_LIST;
            }
        }
        if (type instanceof Class<?> c) {
            if (c == String.class) {
                return ParamType.STRING;
            }
            if (c == Integer.class || c == int.class) {
                return ParamType.INT;
            }
            if (c == Double.class || c == double.class) {
                return ParamType.DOUBLE;
            }
            if (c == Boolean.class || c == boolean.class) {
                return ParamType.BOOLEAN;
            }
            if (c == Duration.class) {
                return ParamType.DURATION;
            }
            if (c.isEnum()) {
                return ParamType.ENUM;
            }
        }
        throw new IllegalStateException("No catalog type for " + type);
    }

    private List<String> options(ScenarioParam meta, Type type) {
        if (meta.options().length > 0) {
            return List.of(meta.options());
        }
        Type t = type instanceof ParameterizedType p ? p.getActualTypeArguments()[0] : type;
        if (t instanceof Class<?> c && c.isEnum()) {
            return Arrays.stream(c.getEnumConstants())
                    .map(constant -> mapper.valueToTree(constant).asString())
                    .toList();
        }
        return List.of();
    }

    /**
     * The Bean Validation annotations of a component. They do not target record components, so Java keeps them on
     * the field (and the accessor and constructor parameter) that the component declares.
     */
    private static Annotation[] constraints(RecordComponent component) {
        try {
            return component
                    .getDeclaringRecord()
                    .getDeclaredField(component.getName())
                    .getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Annotation[] elementAnnotations(AnnotatedType type) {
        return type instanceof AnnotatedParameterizedType p
                ? p.getAnnotatedActualTypeArguments()[0].getAnnotations()
                : new Annotation[0];
    }

    private static Bounds numberBounds(Annotation[] annotations) {
        Number min = null;
        Number max = null;
        for (Annotation a : annotations) {
            switch (a) {
                case Min m -> min = m.value();
                case Max m -> max = m.value();
                case DecimalMin m -> min = new BigDecimal(m.value());
                case DecimalMax m -> max = new BigDecimal(m.value());
                default -> {}
            }
        }
        return new Bounds(min, max);
    }

    /** A {@code duration} is capped by {@code max-duration}; other durations by their own constraint. */
    private Bounds durationBounds(RecordComponent component) {
        DurationMin min = null;
        DurationMax max = null;
        for (Annotation a : constraints(component)) {
            if (a instanceof DurationMin m) {
                min = m;
            } else if (a instanceof DurationMax m) {
                max = m;
            }
        }
        Duration upper =
                max == null ? null : duration(max.days(), max.hours(), max.minutes(), max.seconds(), max.millis());
        if (component.getName().equals("duration")) {
            upper = upper == null || maxDuration.compareTo(upper) < 0 ? maxDuration : upper;
        }
        return new Bounds(
                min == null ? null : duration(min.days(), min.hours(), min.minutes(), min.seconds(), min.millis()),
                upper);
    }

    private static Duration duration(long days, long hours, long minutes, long seconds, long millis) {
        return Duration.ofDays(days)
                .plusHours(hours)
                .plusMinutes(minutes)
                .plusSeconds(seconds)
                .plusMillis(millis);
    }

    private record Bounds(@Nullable Object min, @Nullable Object max) {
        static final Bounds NONE = new Bounds(null, null);
    }

    /** The body of {@code GET /sim/scenarios}. */
    public record Catalog(List<Item> items) {}

    public record Item(String name, String title, String description, Concurrency concurrency, List<Param> params) {}

    /**
     * One parameter. {@code min} and {@code max} are numbers, or ISO-8601 strings for durations; for a
     * {@code DOUBLE_LIST} they apply to each element.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Param(
            String name,
            String label,
            ParamType type,
            boolean required,
            boolean nullable,
            @JsonProperty("default") @Nullable JsonNode defaultValue,
            @Nullable Object min,
            @Nullable Object max,
            @Nullable List<String> options,
            @Nullable List<String> suggestions) {}
}
