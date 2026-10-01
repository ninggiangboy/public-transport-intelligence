package dev.pti.api.etlops.adapter.out.validation;

import dev.pti.api.etlops.application.port.EditedPayloadChecker;
import dev.pti.api.etlops.domain.BusinessKeyChangedException;
import dev.pti.api.etlops.domain.InvalidPayloadException;
import dev.pti.api.etlops.domain.PiiNotAllowedException;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.common.json.MessageJson;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.MessageSchemas;
import dev.pti.common.message.Payload;
import dev.pti.common.pii.PiiScrubber;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The checks of an edited payload of DOC-22 §2, with the schemas, the PII blocklist and the Bean Validation of {@code
 * common}: the same code the ETL runs on a message, so that a payload that passes here passes the {@code SCHEMA} step
 * when it is replayed. The rules that need reference data or the warehouse (DQ-03…13) are not run here; they run when
 * the replay does.
 *
 * <p>A GTFS-realtime payload is checked against the envelope and payload schemas and the Bean Validation of its
 * DTO. A ticketing (CDC) payload is checked only for being a JSON object without PII and with the same business key:
 * its validation lives in {@code etl}, which the API may not depend on, so the replay validates it.
 */
public final class JsonEditedPayloadChecker implements EditedPayloadChecker {

    private static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern SCHEMA_LOCATION = Pattern.compile("^(\\$[^:]*):\\s*(.*)$", Pattern.DOTALL);

    private final MessageSchemas schemas;
    private final Validator validator;
    private final PiiScrubber pii;

    public JsonEditedPayloadChecker(MessageSchemas schemas, Validator validator, PiiScrubber pii) {
        this.schemas = schemas;
        this.validator = validator;
        this.pii = pii;
    }

    @Override
    public Checked check(String source, @Nullable String rawPayload, String edited) {
        if (edited.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("/", "must be at most 1 MiB");
        }
        JsonNode tree = parseObject(edited);
        if (pii.containsBlocked(tree)) {
            throw new PiiNotAllowedException();
        }
        JsonNode original = rawPayload == null ? null : tryParseObject(rawPayload);
        switch (source) {
            case "GTFS_RT_VEHICLE_POSITION" -> checkGtfsRealtime(tree, "VEHICLE_POSITION");
            case "GTFS_RT_TRIP_UPDATE" -> checkGtfsRealtime(tree, "TRIP_UPDATE");
            default -> {
                // CDC payloads: the replay validates them (see the class comment).
            }
        }
        if (original != null && !identity(source, original).equals(identity(source, tree))) {
            throw new BusinessKeyChangedException();
        }
        List<String> changed = new ArrayList<>();
        if (original == null) {
            changed.add("$");
        } else {
            diff("", original, tree, changed);
        }
        return new Checked(MessageJson.mapper().writeValueAsString(tree), changed);
    }

    private static JsonNode parseObject(String text) {
        JsonNode tree;
        try {
            tree = MessageJson.mapper().readTree(text);
        } catch (JacksonException e) {
            throw invalid("/", "is not valid JSON: " + e.getOriginalMessage());
        }
        if (tree == null || !tree.isObject()) {
            throw invalid("/", "must be a JSON object");
        }
        return tree;
    }

    private static @Nullable JsonNode tryParseObject(String text) {
        try {
            JsonNode tree = MessageJson.mapper().readTree(text);
            return tree != null && tree.isObject() ? tree : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private void checkGtfsRealtime(JsonNode tree, String expectedEntity) {
        List<FieldError> errors = new ArrayList<>();
        JsonNode entity = tree.path("entity_type");
        if (entity.isString() && !entity.asString().equals(expectedEntity)) {
            throw new BusinessKeyChangedException();
        }
        for (String message : schemas.validate(tree)) {
            errors.add(fromSchemaMessage(message));
        }
        if (errors.isEmpty()) {
            errors.addAll(beanViolations(tree));
        }
        if (!errors.isEmpty()) {
            throw new InvalidPayloadException("The payload does not match the message schema.", errors);
        }
    }

    private List<FieldError> beanViolations(JsonNode tree) {
        Envelope<? extends Payload> envelope;
        try {
            envelope = MessageJson.toEnvelope(tree);
        } catch (JacksonException e) {
            return List.of(new FieldError("/", e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            return List.of(new FieldError("/", String.valueOf(e.getMessage())));
        }
        Set<ConstraintViolation<Envelope<? extends Payload>>> violations = validator.validate(envelope);
        return violations.stream()
                .sorted(Comparator.comparing(
                        violation -> violation.getPropertyPath().toString()))
                .map(violation ->
                        new FieldError(pointer(violation.getPropertyPath().toString()), violation.getMessage()))
                .toList();
    }

    /** {@code $.payload.position.latitude: must be …} becomes the field {@code /payload/position/latitude}. */
    private static FieldError fromSchemaMessage(String message) {
        Matcher matcher = SCHEMA_LOCATION.matcher(message);
        if (matcher.matches()) {
            return new FieldError(pointer(matcher.group(1).substring(1)), matcher.group(2));
        }
        return new FieldError("/", message);
    }

    /** A dotted property path such as {@code payload.stopTimeUpdates[0].delay} as a JSON Pointer. */
    private static String pointer(String path) {
        String trimmed = path.startsWith(".") ? path.substring(1) : path;
        if (trimmed.isEmpty()) {
            return "/";
        }
        return "/" + trimmed.replace("[", ".").replace("]", "").replace('.', '/');
    }

    /** What makes the record the record (DOC-22 §2): the entity type and the key of the source. */
    private static Map<String, String> identity(String source, JsonNode tree) {
        return switch (source) {
            case "GTFS_RT_VEHICLE_POSITION" ->
                Map.of(
                        "entity", text(tree.path("entity_type")),
                        "vehicle", text(tree.path("payload").path("vehicle_id")),
                        "eventTimestamp", text(tree.path("event_timestamp")));
            case "GTFS_RT_TRIP_UPDATE" ->
                Map.of(
                        "entity", text(tree.path("entity_type")),
                        "trip", text(tree.path("payload").path("trip_id")),
                        "startDate", text(tree.path("payload").path("start_date")));
            case "TICKETING_SALES" -> Map.of("transaction", text(tree.path("transaction_id")));
            default -> Map.of("salePoint", text(tree.path("sale_point_id")));
        };
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? "" : node.asString();
    }

    /** The paths whose values differ; objects are compared member by member, anything else as a whole. */
    private static void diff(String path, JsonNode before, JsonNode after, List<String> changed) {
        if (before.isObject() && after.isObject()) {
            Set<String> names = new TreeSet<>();
            before.propertyNames().forEach(names::add);
            after.propertyNames().forEach(names::add);
            for (String name : names) {
                String child = path.isEmpty() ? name : path + "." + name;
                JsonNode left = before.get(name);
                JsonNode right = after.get(name);
                if (left == null || right == null) {
                    changed.add(child);
                } else {
                    diff(child, left, right, changed);
                }
            }
        } else if (!Objects.equals(before, after)) {
            changed.add(path);
        }
    }

    private static InvalidPayloadException invalid(String field, String message) {
        return new InvalidPayloadException("The payload is not valid.", List.of(new FieldError(field, message)));
    }
}
