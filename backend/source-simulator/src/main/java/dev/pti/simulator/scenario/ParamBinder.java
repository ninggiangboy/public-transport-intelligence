package dev.pti.simulator.scenario;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the body of {@code POST /sim/scenarios/{name}} into the scenario's parameter record (DOC-25 §8): the
 * request's fields over the defaults, then Bean Validation, then the scenario's own checks. Every problem is
 * reported per field as {@code invalid-param}.
 */
public final class ParamBinder {

    private final JsonMapper mapper;
    private final Validator validator;

    public ParamBinder(JsonMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public <P extends Record> P bind(Scenario<P> scenario, @Nullable JsonNode body) {
        Set<String> names = Arrays.stream(scenario.paramsType().getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        ObjectNode merged = mapper.valueToTree(scenario.defaults());
        List<ScenarioException.FieldError> errors = new ArrayList<>();
        if (body != null && !body.isNull() && !body.isMissingNode()) {
            if (!body.isObject()) {
                throw new ScenarioException(
                        ScenarioException.Problem.INVALID_PARAM, "The request body must be a JSON object.");
            }
            for (String field : body.propertyNames()) {
                if (names.contains(field)) {
                    merged.set(field, body.get(field));
                } else {
                    errors.add(new ScenarioException.FieldError(field, "unknown parameter"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        P params;
        try {
            params = mapper.treeToValue(merged, scenario.paramsType());
        } catch (JacksonException e) {
            throw invalid(List.of(new ScenarioException.FieldError(field(e), "has a value of the wrong type")));
        }
        Set<ConstraintViolation<P>> violations = validator.validate(params);
        if (!violations.isEmpty()) {
            throw invalid(violations.stream()
                    .map(v -> new ScenarioException.FieldError(field(v.getPropertyPath()), v.getMessage()))
                    .sorted(Comparator.comparing(ScenarioException.FieldError::field)
                            .thenComparing(ScenarioException.FieldError::message))
                    .toList());
        }
        List<ScenarioException.FieldError> checks = scenario.check(params);
        if (!checks.isEmpty()) {
            throw invalid(checks);
        }
        return params;
    }

    private static ScenarioException invalid(List<ScenarioException.FieldError> errors) {
        return new ScenarioException(
                ScenarioException.Problem.INVALID_PARAM, "A scenario parameter is invalid.", errors);
    }

    /** The top-level parameter a violation is about, e.g. {@code kinds} for {@code kinds[0].<list element>}. */
    private static String field(Path path) {
        Iterator<Path.Node> nodes = path.iterator();
        return nodes.hasNext() ? nodes.next().getName() : "";
    }

    private static String field(JacksonException e) {
        return e.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(name -> name != null)
                .findFirst()
                .orElse("");
    }
}
